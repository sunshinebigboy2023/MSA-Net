package com.yupi.usercenter.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MsaPropertiesTest {

    @Test
    void defaultsPointToLocalMsaAndRuntimeUploads() {
        MsaProperties properties = new MsaProperties();

        Assertions.assertEquals("http://127.0.0.1:8000", properties.getBaseUrl());
        Assertions.assertEquals("runtime/uploads", properties.getUploadDir());
        Assertions.assertEquals(200L, properties.getMaxVideoSizeMb());
        Assertions.assertNull(properties.getCallbackToken());
    }
}
