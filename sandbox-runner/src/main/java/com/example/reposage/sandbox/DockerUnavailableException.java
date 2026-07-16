package com.example.reposage.sandbox;

final class DockerUnavailableException extends RuntimeException {

    DockerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
