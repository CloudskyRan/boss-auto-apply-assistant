package com.getjobs.application.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A client-safe failure. Reasons must never contain a question, credentials or model response. */
public class ExamAssistantException extends ResponseStatusException {
    public ExamAssistantException(HttpStatus status, String message) {
        super(status, message);
    }
}
