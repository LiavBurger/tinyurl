package com.handson.tinyurl.repository;


import com.handson.tinyurl.model.UserClick;
import com.handson.tinyurl.model.UserClickKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;

public interface UserClickRepository extends CassandraRepository<UserClick, UserClickKey> {

    Iterable<UserClick> findByUserClickKeyUserName(String userName);

}
