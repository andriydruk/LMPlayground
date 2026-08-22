package com.druk.llamacpp;

oneway interface ITranscriptionCallback {
    void onTranscription(String text);
    void onTranscriptionError(String message);
}
