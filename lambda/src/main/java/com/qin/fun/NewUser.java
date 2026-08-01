package com.qin.fun;

import com.qin.User;

import java.time.LocalDate;

@FunctionalInterface
public interface NewUser {
    User apply(Long id, String name, Integer status, String mobile, Integer age,
               LocalDate birthday, String introduction, Integer sex, String cardID, String address);
}
