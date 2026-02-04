package com.nptechon.smartamp.control;

import com.nptechon.smartamp.control.dto.ControlResponseDto;
import com.nptechon.smartamp.global.error.CustomException;
import com.nptechon.smartamp.global.error.ErrorCode;
import com.nptechon.smartamp.tcp.protocol.payload.AmpPower;
import com.nptechon.smartamp.tcp.server.sender.CommandSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AmpService {

    private final CommandSender commandSender;

    public ControlResponseDto setPower(String ampIdRaw, String powerRaw) {
        int ampId = parseAmpId(ampIdRaw);

        String power = normalize(powerRaw);
        AmpPower command = switch (power) {
            case "ON", "1" -> AmpPower.ON;
            case "OFF", "0" -> AmpPower.OFF;
            default -> throw new CustomException(ErrorCode.INVALID_REQUEST, "power 값은 ON 또는 OFF 여야 합니다.");
        };

        // 1) 연결 여부 확인 + 2) 명령 전송 (0x02 payload: 1/0)
        try {
            commandSender.sendPower(ampId, command);
        } catch (IllegalStateException e) {
            // AmpTcpSender에서 "AMP not connected" 같은 예외 던지게 해둔 경우
            throw new CustomException(ErrorCode.DEVICE_OFFLINE, "AMP가 TCP로 연결되어 있지 않습니다.");
        } catch (Exception e) {
            log.error("power control failed ampId={} on={}", ampId, command, e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "전원 제어 중 오류가 발생했습니다.");
        }

        return new ControlResponseDto(String.valueOf(ampId), power);
    }

    private int parseAmpId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_REQUEST, "ampId는 필수입니다.");
        }
        try {
            int id = Integer.parseInt(raw.trim());
            if (id < 1 || id > 100) {
                throw new CustomException(ErrorCode.INVALID_REQUEST, "ampId는 1~100 범위여야 합니다.");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_REQUEST, "ampId는 숫자여야 합니다.");
        }
    }

    private String normalize(String powerRaw) {
        if (powerRaw == null) return "";
        return powerRaw.trim().toUpperCase();
    }
}
