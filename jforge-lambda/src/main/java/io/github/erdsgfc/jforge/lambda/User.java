package io.github.erdsgfc.jforge.lambda;

import java.time.LocalDate;

public class User {

    private Long id;
    private String name;
    private Integer status;
    private String mobile;
    private Integer age;
    private LocalDate birthday;
    private String introduction;
    private Integer sex;
    private String cardID;
    private String address;

    public User() {
    }
    public User(Long id, String name, Integer status, String mobile, Integer age,
                LocalDate birthday, String introduction, Integer sex, String cardID, String address) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.mobile = mobile;
        this.age = age;
        this.birthday = birthday;
        this.introduction = introduction;
        this.sex = sex;
        this.cardID = cardID;
        this.address = address;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; }

    public String getIntroduction() { return introduction; }
    public void setIntroduction(String introduction) { this.introduction = introduction; }

    public Integer getSex() { return sex; }
    public void setSex(Integer sex) { this.sex = sex; }

    public String getCardID() { return cardID; }
    public void setCardID(String cardID) { this.cardID = cardID; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
}
