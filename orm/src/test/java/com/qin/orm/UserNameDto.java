package com.qin.orm;

/** DTO projection for partial-field queries (component order = SELECT column order). */
public record UserNameDto(long id, String userName) {
}
