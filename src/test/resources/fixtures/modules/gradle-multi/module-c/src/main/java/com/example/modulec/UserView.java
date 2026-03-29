package com.example.modulec;

import com.example.moduleb.UserService;

public class UserView {
    private final UserService userService = new UserService();

    public void render() {
        userService.findUser("test");
    }
}
