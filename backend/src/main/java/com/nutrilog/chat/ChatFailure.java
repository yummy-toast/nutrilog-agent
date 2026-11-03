package com.nutrilog.chat;
public class ChatFailure extends RuntimeException {
    private final String code;
    private final int status;
    public ChatFailure(String code, int status) { super(code); this.code = code; this.status = status; }
    public String code() { return code; }
    public int status() { return status; }
}
