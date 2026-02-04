package com.nptechon.smartamp.control.dto;

import lombok.*;

@Getter
@AllArgsConstructor
public class ControlResponseDto {
    private String ampId;
    private String powerCommand; // "ON" / "OFF"
}
