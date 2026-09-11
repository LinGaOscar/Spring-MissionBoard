package com.missionboard;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 不另開 config 類別放這段：全專案只需要標題與版號，掛在啟動類最省結構
@OpenAPIDefinition(info = @Info(title = "MissionBoard API", version = "v1"))
@SpringBootApplication
public class MissionBoardApplication {
    public static void main(String[] args) {
        SpringApplication.run(MissionBoardApplication.class, args);
    }
}
