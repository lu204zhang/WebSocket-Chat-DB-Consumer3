package vito.persistence.model;

import lombok.Data;

@Data
public class TopUser {
    private String userId;
    private String username;
    private int messageCount;
}