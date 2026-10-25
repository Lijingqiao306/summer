package org.example.ykd_summer.exception;

import lombok.Getter;

@Getter
public class WeatherException extends RuntimeException {

    private final String errorCode;

    public WeatherException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
