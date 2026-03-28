package com.example.migration;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Service;

/**
 * A pure service class with javax.servlet imports that need package renaming to jakarta.servlet.
 * No Vaadin 7 types — should produce CHANGE_PACKAGE actions with auto=YES.
 */
@Service
public class PureServiceClass {

    public void handleRequest(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI();
        int status = response.getStatus();
        System.out.println("Handling request: " + path + " (status " + status + ")");
    }

    public String extractHeader(HttpServletRequest request, String headerName) {
        return request.getHeader(headerName);
    }
}
