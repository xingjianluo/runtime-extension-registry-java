package com.github.xingjianluo.runtimeextensionregistryjava.extension.web;

import java.util.Map;

/** 对外统一错误体：错误码、可读消息、定位上下文；不含任何堆栈。 */
public record ApiError(String code, String message, Map<String, Object> context) {
}
