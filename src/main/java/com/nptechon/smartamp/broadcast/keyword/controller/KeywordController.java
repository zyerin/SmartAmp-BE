package com.nptechon.smartamp.broadcast.keyword.controller;

import com.nptechon.smartamp.broadcast.keyword.dto.KeywordBroadcastDto;
import com.nptechon.smartamp.broadcast.keyword.service.KeywordService;
import com.nptechon.smartamp.global.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/keyword")
public class KeywordController {

    private final KeywordService keywordService;

    @PostMapping("/broadcast")
    public ResponseEntity<ApiResponse<String>> broadcast(@RequestBody KeywordBroadcastDto dto, HttpServletRequest request) {
        log.info("keyword broadcast api request!! ampId: {}, content: {}", dto.getAmpId(), dto.getContent());
        String result = "success";
//        String result = keywordService.broadcastTts(dto.getAmpId(), dto.getContent());

        return ResponseEntity.ok(
                ApiResponse.ok(
                        "ok",
                        "키워드 TTS 방송 성공",
                        result,
                        request.getRequestId(),
                        request.getRequestURI()
                )
        );
    }
}
