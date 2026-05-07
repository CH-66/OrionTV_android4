package com.oriontv.legacy.api;

public interface ApiCallback<T> {
    void onSuccess(T value);
    void onError(Throwable error);
}
