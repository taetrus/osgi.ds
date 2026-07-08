package com.kk.pde.ds.imp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class GreetTest {

    @Test
    void greetingReturnsHelloWorld() {
        assertEquals("Hello world!", new Greet().greeting());
    }
}
