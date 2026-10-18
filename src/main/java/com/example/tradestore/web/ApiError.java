package com.example.tradestore.web;

import java.time.Instant;

/** Error contract: {"status":400,"error":"LowerVersionException","message":"...","timestamp":"..."} */
public record ApiError(int status, String error, String message, Instant timestamp) {
}
