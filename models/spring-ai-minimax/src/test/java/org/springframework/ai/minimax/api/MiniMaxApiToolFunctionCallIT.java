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

package org.springframework.ai.minimax.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.minimax.api.MiniMaxApi.ChatCompletion;
import org.springframework.ai.minimax.api.MiniMaxApi.ChatCompletionMessage;
import org.springframework.ai.minimax.api.MiniMaxApi.ChatCompletionMessage.Role;
import org.springframework.ai.minimax.api.MiniMaxApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.minimax.api.MiniMaxApi.ChatCompletionRequest;
import org.springframework.ai.minimax.api.MiniMaxApi.ChatCompletionRequest.ToolChoiceBuilder;
import org.springframework.ai.minimax.api.MiniMaxApi.FunctionTool.Type;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ai.minimax.api.MiniMaxApi.ChatModel.*;

/**
 * @author Geng Rong
 */
@EnabledIfEnvironmentVariable(named = "MINIMAX_API_KEY", matches = ".+")
public class MiniMaxApiToolFunctionCallIT {

	private final Logger logger = LoggerFactory.getLogger(MiniMaxApiToolFunctionCallIT.class);

	MockWeatherService weatherService = new MockWeatherService();

	MiniMaxApi miniMaxApi = new MiniMaxApi(System.getenv("MINIMAX_API_KEY"));

	@SuppressWarnings("null")
	@Test
	public void toolFunctionCall() {

		// Step 1: send the conversation and available functions to the model
		var message = new ChatCompletionMessage("What's the weather like in San Francisco?", Role.USER);

		var functionTool = new MiniMaxApi.FunctionTool(Type.FUNCTION, new MiniMaxApi.FunctionTool.Function(
				"Get the weather in location. Return temperature in 30°F or 30°C format.", "getCurrentWeather", """
						{
							"type": "object",
							"properties": {
								"location": {
									"type": "string",
									"description": "The city and state e.g. San Francisco, CA"
								},
								"lat": {
									"type": "number",
									"description": "The city latitude"
								},
								"lon": {
									"type": "number",
									"description": "The city longitude"
								},
								"unit": {
									"type": "string",
									"enum": ["C", "F"]
								}
							},
							"required": ["location", "lat", "lon", "unit"]
						}
						"""));

		List<ChatCompletionMessage> messages = new ArrayList<>(List.of(message));

		ChatCompletionRequest chatCompletionRequest = new ChatCompletionRequest(messages, ABAB_6_Chat.value,
				List.of(functionTool), ToolChoiceBuilder.AUTO);

		ResponseEntity<ChatCompletion> chatCompletion = miniMaxApi.chatCompletionEntity(chatCompletionRequest);

		assertThat(chatCompletion.getBody()).isNotNull();
		assertThat(chatCompletion.getBody().choices()).isNotEmpty();

		ChatCompletionMessage responseMessage = chatCompletion.getBody().choices().get(0).message();

		assertThat(responseMessage.role()).isEqualTo(Role.ASSISTANT);
		assertThat(responseMessage.toolCalls()).isNotNull();

		messages.add(responseMessage);

		// Send the info for each function call and function response to the model.
		for (ToolCall toolCall : responseMessage.toolCalls()) {
			var functionName = toolCall.function().name();
			if ("getCurrentWeather".equals(functionName)) {
				MockWeatherService.Request weatherRequest = fromJson(toolCall.function().arguments(),
						MockWeatherService.Request.class);

				MockWeatherService.Response weatherResponse = weatherService.apply(weatherRequest);

				// extend conversation with function response.
				messages.add(new ChatCompletionMessage("" + weatherResponse.temp() + weatherRequest.unit(), Role.TOOL,
						functionName, toolCall.id(), null));
			}
		}

		var functionResponseRequest = new ChatCompletionRequest(messages, ABAB_6_Chat.value, 0.5F);

		ResponseEntity<ChatCompletion> chatCompletion2 = miniMaxApi.chatCompletionEntity(functionResponseRequest);

		logger.info("Final response: " + chatCompletion2.getBody());

		assertThat(Objects.requireNonNull(chatCompletion2.getBody()).choices()).isNotEmpty();

		assertThat(chatCompletion2.getBody().choices().get(0).message().role()).isEqualTo(Role.ASSISTANT);
		assertThat(chatCompletion2.getBody().choices().get(0).message().content()).contains("San Francisco")
			.containsAnyOf("30.0°C", "30°C", "30.0°F", "30°F");
	}

	private static <T> T fromJson(String json, Class<T> targetClass) {
		try {
			return new ObjectMapper().readValue(json, targetClass);
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

}