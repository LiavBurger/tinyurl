package com.handson.tinyurl.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handson.tinyurl.model.NewTinyRequest;
import com.handson.tinyurl.model.User;
import com.handson.tinyurl.model.UserClickOut;
import com.handson.tinyurl.repository.UserClickRepository;
import com.handson.tinyurl.repository.UserRepository;
import com.handson.tinyurl.service.Redis;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static com.handson.tinyurl.model.User.UserBuilder.anUser;
import static com.handson.tinyurl.model.UserClick.UserClickBuilder.anUserClick;
import static com.handson.tinyurl.model.UserClickKey.UserClickKeyBuilder.anUserClickKey;
import static com.handson.tinyurl.util.Dates.getCurMonth;
import static org.springframework.data.util.StreamUtils.createStreamFromIterator;

@RestController
public class AppController {

    private static final int MAX_RETRIES = 4;
    private static final int TINY_LENGTH = 6;

    private final Redis redis;
    private final Random random = new Random();
    private final ObjectMapper om;
    private final String baseUrl;
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final UserClickRepository userClickRepository;

    public AppController(@Autowired Redis redis,
                         @Autowired ObjectMapper om,
                         @Value("${base.url}") String baseUrl,
                         @Autowired UserRepository userRepository,
                         @Autowired MongoTemplate mongoTemplate,
                         @Autowired UserClickRepository userClickRepository) {
        this.redis = redis;
        this.om = om;
        this.baseUrl = baseUrl;
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.userClickRepository = userClickRepository;
    }



    @PostMapping("/tiny")
    public String generate(@RequestBody NewTinyRequest request) throws JsonProcessingException {
        String tinyCode = generateTinyCode();
        int i = 0;
        while (!redis.set(tinyCode, om.writeValueAsString(request)) && i < MAX_RETRIES) {
            tinyCode = generateTinyCode();
            i++;
        }
        if (i == MAX_RETRIES) throw new RuntimeException("SPACE IS FULL");
        return baseUrl + tinyCode + "/";
    }

    @GetMapping("/{tiny}/")
    public RedirectView getTiny(@PathVariable String tiny) throws JsonProcessingException {
        Object tinyRequestStr = redis.get(tiny);
        NewTinyRequest tinyRequest = om.readValue(tinyRequestStr.toString(),NewTinyRequest.class);
        if (tinyRequest.getLongUrl() != null) {
            String userName = tinyRequest.getUsername();
            if ( userName != null) {
                incrementMongoField(userName, "allUrlClicks");
                incrementMongoField(userName,
                        "shorts."  + tiny + ".clicks." + getCurMonth());
                userClickRepository.save(anUserClick().userClickKey(anUserClickKey().withUserName(userName).withClickTime(new Date()).build())
                        .tiny(tiny).longUrl(tinyRequest.getLongUrl()).build());
            }
            return new RedirectView(tinyRequest.getLongUrl());
        } else {
            throw new RuntimeException(tiny + " not found");
        }
    }


    private String generateTinyCode() {
        String charPool = "ABCDEFHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder res = new StringBuilder();
        for (int i = 0; i < TINY_LENGTH; i++) {
            res.append(charPool.charAt(random.nextInt(charPool.length())));
        }
        return res.toString();
    }



    @PostMapping(value = "/user")
    public User createUser(@RequestParam String name) {
        User user = anUser().withName(name).build();
        user = userRepository.insert(user);
        return user;
    }

    @GetMapping(value = "/user/{name}")
    public User getUser(@PathVariable String name) {
        return userRepository.findFirstByName(name);
    }

    private void incrementMongoField(String userName, String key){
        Query query = Query.query(Criteria.where("name").is(userName));
        Update update = new Update().inc(key, 1);
        mongoTemplate.updateFirst(query, update, "users");
    }



    @RequestMapping(value = "/user/{name}/clicks", method = RequestMethod.GET)
    public List<UserClickOut> getUserClicks(@RequestParam String name) {
        return createStreamFromIterator( userClickRepository.findByUserClickKeyUserName(name).iterator())
                .map(UserClickOut::of)
                .collect(Collectors.toList());
    }
}
