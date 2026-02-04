package com.nptechon.smartamp.control.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ControlRequestDto {
    private String ampId;
    private String powerCommand; // "ON" or "OFF"
}
