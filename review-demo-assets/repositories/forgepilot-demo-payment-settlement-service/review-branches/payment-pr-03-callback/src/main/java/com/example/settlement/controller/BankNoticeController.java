package com.example.settlement.controller;
import java.util.Map;
public class BankNoticeController {
    public int accept(byte[] body, Map<String, String> headers) { return body.length > 0 ? 200 : 400; }
}
