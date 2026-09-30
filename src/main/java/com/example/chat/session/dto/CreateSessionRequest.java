package com.example.chat.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
	@NotBlank @Size(max = 64) String participantA,
	@NotBlank @Size(max = 64) String participantB
) {

}
