/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.minimax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.StreamingChatClient;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.ai.minimax.api.common.MiniMaxApiException;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.function.AbstractFunctionCallSupport;
import org.springframework.ai.model.function.FunctionCallbackContext;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ChatClient} and {@link StreamingChatClient} implementation for
 * {@literal MiniMax} backed by {@link MiniMaxApi}.
 *
 * @author Geng Rong
 * @see ChatClient
 * @see StreamingChatClient
 * @see MiniMaxApi
 * @since 1.0.0 M1
 */
public class MiniMaxChatClient extends
		AbstractFunctionCallSupport<MiniMaxApi.ChatCompletionMessage, MiniMaxApi.ChatCompletionRequest, ResponseEntity<MiniMaxApi.ChatCompletion>>
		implements ChatClient, StreamingChatClient {

	private static final Logger logger = LoggerFactory.getLogger(MiniMaxChatClient.class);

	/**
	 * The default options used for the chat completion requests.
	 */
	private MiniMaxChatOptions defaultOptions;

	/**
	 * The retry template used to retry the MiniMax API calls.
	 */
	public final RetryTemplate retryTemplate;

	/**
	 * Low-level access to the MiniMax API.
	 */
	private final MiniMaxApi miniMaxApi;

	/**
	 * Creates an instance of the MiniMaxChatClient.
	 * @param miniMaxApi The MiniMaxApi instance to be used for interacting with the
	 * MiniMax Chat API.
	 * @throws IllegalArgumentException if MiniMaxApi is null
	 */
	public MiniMaxChatClient(MiniMaxApi miniMaxApi) {
		this(miniMaxApi,
				MiniMaxChatOptions.builder().withModel(MiniMaxApi.DEFAULT_CHAT_MODEL).withTemperature(0.7f).build());
	}

	/**
	 * Initializes an instance of the MiniMaxChatClient.
	 * @param miniMaxApi The MiniMaxApi instance to be used for interacting with the
	 * MiniMax Chat API.
	 * @param options The MiniMaxChatOptions to configure the chat client.
	 */
	public MiniMaxChatClient(MiniMaxApi miniMaxApi, MiniMaxChatOptions options) {
		this(miniMaxApi, options, null, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * Initializes a new instance of the MiniMaxChatClient.
	 * @param miniMaxApi The MiniMaxApi instance to be used for interacting with the
	 * MiniMax Chat API.
	 * @param options The MiniMaxChatOptions to configure the chat client.
	 * @param functionCallbackContext The function callback context.
	 * @param retryTemplate The retry template.
	 */
	public MiniMaxChatClient(MiniMaxApi miniMaxApi, MiniMaxChatOptions options,
			FunctionCallbackContext functionCallbackContext, RetryTemplate retryTemplate) {
		super(functionCallbackContext);
		Assert.notNull(miniMaxApi, "MiniMaxApi must not be null");
		Assert.notNull(options, "Options must not be null");
		Assert.notNull(retryTemplate, "RetryTemplate must not be null");
		this.miniMaxApi = miniMaxApi;
		this.defaultOptions = options;
		this.retryTemplate = retryTemplate;
	}

	@Override
	public ChatResponse call(Prompt prompt) {

		MiniMaxApi.ChatCompletionRequest request = createRequest(prompt, false);

		return this.retryTemplate.execute(ctx -> {

			ResponseEntity<MiniMaxApi.ChatCompletion> completionEntity = this.callWithFunctionSupport(request);

			var chatCompletion = completionEntity.getBody();
			if (chatCompletion == null) {
				logger.warn("No chat completion returned for prompt: {}", prompt);
				return new ChatResponse(List.of());
			}

			if (chatCompletion.baseResponse() != null && chatCompletion.baseResponse().statusCode() != 0) {
				throw new MiniMaxApiException(chatCompletion.baseResponse().message());
			}

			List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
				return new Generation(choice.message().content(), toMap(chatCompletion.id(), choice))
					.withGenerationMetadata(ChatGenerationMetadata.from(choice.finishReason().name(), null));
			}).toList();

			return new ChatResponse(generations);
		});
	}

	private Map<String, Object> toMap(String id, MiniMaxApi.ChatCompletion.Choice choice) {
		Map<String, Object> map = new HashMap<>();

		var message = choice.message();
		if (message.role() != null) {
			map.put("role", message.role().name());
		}
		if (choice.finishReason() != null) {
			map.put("finishReason", choice.finishReason().name());
		}
		map.put("id", id);
		return map;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {

		MiniMaxApi.ChatCompletionRequest request = createRequest(prompt, true);

		return this.retryTemplate.execute(ctx -> {

			Flux<MiniMaxApi.ChatCompletionChunk> completionChunks = this.miniMaxApi.chatCompletionStream(request);

			// For chunked responses, only the first chunk contains the choice role.
			// The rest of the chunks with same ID share the same role.
			ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();

			// Convert the ChatCompletionChunk into a ChatCompletion to be able to reuse
			// the function call handling logic.
			return completionChunks.map(chunk -> chunkToChatCompletion(chunk)).map(chatCompletion -> {
				try {
					chatCompletion = handleFunctionCallOrReturn(request, ResponseEntity.of(Optional.of(chatCompletion)))
						.getBody();

					@SuppressWarnings("null")
					String id = chatCompletion.id();

					List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
						if (choice.message().role() != null) {
							roleMap.putIfAbsent(id, choice.message().role().name());
						}
						String finish = (choice.finishReason() != null ? choice.finishReason().name() : "");
						var generation = new Generation(choice.message().content(),
								Map.of("id", id, "role", roleMap.get(id), "finishReason", finish));
						if (choice.finishReason() != null) {
							generation = generation.withGenerationMetadata(
									ChatGenerationMetadata.from(choice.finishReason().name(), null));
						}
						return generation;
					}).toList();

					return new ChatResponse(generations);
				}
				catch (Exception e) {
					logger.error("Error processing chat completion", e);
					return new ChatResponse(List.of());
				}

			});
		});
	}

	/**
	 * Convert the ChatCompletionChunk into a ChatCompletion. The Usage is set to null.
	 * @param chunk the ChatCompletionChunk to convert
	 * @return the ChatCompletion
	 */
	private MiniMaxApi.ChatCompletion chunkToChatCompletion(MiniMaxApi.ChatCompletionChunk chunk) {
		List<MiniMaxApi.ChatCompletion.Choice> choices = chunk.choices().stream().map(cc -> {
			MiniMaxApi.ChatCompletionMessage delta = cc.delta();
			if (delta == null) {
				delta = new MiniMaxApi.ChatCompletionMessage("", MiniMaxApi.ChatCompletionMessage.Role.ASSISTANT);
			}
			return new MiniMaxApi.ChatCompletion.Choice(cc.finishReason(), cc.index(), delta, cc.logprobs());
		}).toList();

		return new MiniMaxApi.ChatCompletion(chunk.id(), choices, chunk.created(), chunk.model(),
				chunk.systemFingerprint(), "chat.completion", null, null);
	}

	/**
	 * Accessible for testing.
	 */
	MiniMaxApi.ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {

		Set<String> functionsForThisRequest = new HashSet<>();

		List<MiniMaxApi.ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions()
			.stream()
			.map(m -> new MiniMaxApi.ChatCompletionMessage(m.getContent(),
					MiniMaxApi.ChatCompletionMessage.Role.valueOf(m.getMessageType().name())))
			.toList();

		MiniMaxApi.ChatCompletionRequest request = new MiniMaxApi.ChatCompletionRequest(chatCompletionMessages, stream);

		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof ChatOptions runtimeOptions) {
				MiniMaxChatOptions updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(runtimeOptions,
						ChatOptions.class, MiniMaxChatOptions.class);

				Set<String> promptEnabledFunctions = this.handleFunctionCallbackConfigurations(updatedRuntimeOptions,
						IS_RUNTIME_CALL);
				functionsForThisRequest.addAll(promptEnabledFunctions);

				request = ModelOptionsUtils.merge(updatedRuntimeOptions, request,
						MiniMaxApi.ChatCompletionRequest.class);
			}
			else {
				throw new IllegalArgumentException("Prompt options are not of type ChatOptions: "
						+ prompt.getOptions().getClass().getSimpleName());
			}
		}

		if (this.defaultOptions != null) {

			Set<String> defaultEnabledFunctions = this.handleFunctionCallbackConfigurations(this.defaultOptions,
					!IS_RUNTIME_CALL);

			functionsForThisRequest.addAll(defaultEnabledFunctions);

			request = ModelOptionsUtils.merge(request, this.defaultOptions, MiniMaxApi.ChatCompletionRequest.class);
		}

		// Add the enabled functions definitions to the request's tools parameter.
		if (!CollectionUtils.isEmpty(functionsForThisRequest)) {

			request = ModelOptionsUtils.merge(
					MiniMaxChatOptions.builder().withTools(this.getFunctionTools(functionsForThisRequest)).build(),
					request, MiniMaxApi.ChatCompletionRequest.class);
		}

		return request;
	}

	private String fromMediaData(MimeType mimeType, Object mediaContentData) {
		if (mediaContentData instanceof byte[] bytes) {
			// Assume the bytes are an image. So, convert the bytes to a base64 encoded
			// following the prefix pattern.
			return String.format("data:%s;base64,%s", mimeType.toString(), Base64.getEncoder().encodeToString(bytes));
		}
		else if (mediaContentData instanceof String text) {
			// Assume the text is a URLs or a base64 encoded image prefixed by the user.
			return text;
		}
		else {
			throw new IllegalArgumentException(
					"Unsupported media data type: " + mediaContentData.getClass().getSimpleName());
		}
	}

	private List<MiniMaxApi.FunctionTool> getFunctionTools(Set<String> functionNames) {
		return this.resolveFunctionCallbacks(functionNames).stream().map(functionCallback -> {
			var function = new MiniMaxApi.FunctionTool.Function(functionCallback.getDescription(),
					functionCallback.getName(), functionCallback.getInputTypeSchema());
			return new MiniMaxApi.FunctionTool(function);
		}).toList();
	}

	@Override
	protected MiniMaxApi.ChatCompletionRequest doCreateToolResponseRequest(
			MiniMaxApi.ChatCompletionRequest previousRequest, MiniMaxApi.ChatCompletionMessage responseMessage,
			List<MiniMaxApi.ChatCompletionMessage> conversationHistory) {

		// Every tool-call item requires a separate function call and a response (TOOL)
		// message.
		for (MiniMaxApi.ChatCompletionMessage.ToolCall toolCall : responseMessage.toolCalls()) {

			var functionName = toolCall.function().name();
			String functionArguments = toolCall.function().arguments();

			if (!this.functionCallbackRegister.containsKey(functionName)) {
				throw new IllegalStateException("No function callback found for function name: " + functionName);
			}

			String functionResponse = this.functionCallbackRegister.get(functionName).call(functionArguments);

			// Add the function response to the conversation.
			conversationHistory.add(new MiniMaxApi.ChatCompletionMessage(functionResponse,
					MiniMaxApi.ChatCompletionMessage.Role.TOOL, functionName, toolCall.id(), null));
		}

		// Recursively call chatCompletionWithTools until the model doesn't call a
		// functions anymore.
		MiniMaxApi.ChatCompletionRequest newRequest = new MiniMaxApi.ChatCompletionRequest(conversationHistory, false);
		newRequest = ModelOptionsUtils.merge(newRequest, previousRequest, MiniMaxApi.ChatCompletionRequest.class);

		return newRequest;
	}

	@Override
	protected List<MiniMaxApi.ChatCompletionMessage> doGetUserMessages(MiniMaxApi.ChatCompletionRequest request) {
		return request.messages();
	}

	@Override
	protected MiniMaxApi.ChatCompletionMessage doGetToolResponseMessage(
			ResponseEntity<MiniMaxApi.ChatCompletion> chatCompletion) {
		return chatCompletion.getBody().choices().iterator().next().message();
	}

	@Override
	protected ResponseEntity<MiniMaxApi.ChatCompletion> doChatCompletion(MiniMaxApi.ChatCompletionRequest request) {
		return this.miniMaxApi.chatCompletionEntity(request);
	}

	@Override
	protected Flux<ResponseEntity<MiniMaxApi.ChatCompletion>> doChatCompletionStream(
			MiniMaxApi.ChatCompletionRequest request) {
		throw new RuntimeException("Streaming Function calling is not supported");
	}

	@Override
	protected boolean isToolFunctionCall(ResponseEntity<MiniMaxApi.ChatCompletion> chatCompletion) {
		var body = chatCompletion.getBody();
		if (body == null) {
			return false;
		}

		var choices = body.choices();
		if (CollectionUtils.isEmpty(choices)) {
			return false;
		}

		var choice = choices.get(0);
		var message = choice.message();
		return message != null && !CollectionUtils.isEmpty(choice.message().toolCalls())
				&& choice.finishReason() == MiniMaxApi.ChatCompletionFinishReason.TOOL_CALLS;
	}

}
