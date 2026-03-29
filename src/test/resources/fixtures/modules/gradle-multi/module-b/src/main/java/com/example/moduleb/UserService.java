package com.example.moduleb;

import com.example.modulea.BaseEntity;

public class UserService {
    public BaseEntity findUser(String name) {
        BaseEntity entity = new BaseEntity();
        entity.setName(name);
        return entity;
    }
}
