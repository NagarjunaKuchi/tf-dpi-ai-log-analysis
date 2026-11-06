package tf.dpi.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DpiAgenticRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(DpiAgenticRagApplication.class, args);
    }
}
