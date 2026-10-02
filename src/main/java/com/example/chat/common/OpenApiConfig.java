package com.example.chat.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI chatOpenApi() {
		return new OpenAPI().info(new Info()
				.title("1:1 Chat Event Sourcing API")
				.version("v1")
				.description("""
						모든 상태 변경은 세션 단위 이벤트(seq)로 저장되며, 이벤트 리플레이로 특정 시점 상태를 복원한다.

						**멱등성**: 같은 `clientEventId` 재전송 시 최초 결과를 `200` + `Idempotent-Replayed: true`로 반환.
						**순서**: 서버가 세션별로 발급하는 `seq`가 유일한 순서 기준.

						**WebSocket** (OpenAPI 범위 밖): `ws://{host}/ws?sessionId=&userId=&lastSeq=`
						서버 메시지 `{kind: ACK|EVENT|ERROR|RESUME, data}` — 상세는 docs/02-api-spec.md
						"""));
	}
}
