package com.example.chat.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 현재 시각을 Clock 빈으로 주입받는다.
 * 테스트에서 시간을 고정할 수 있어 복원 결정성 검증(5일차)에 필요하다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
