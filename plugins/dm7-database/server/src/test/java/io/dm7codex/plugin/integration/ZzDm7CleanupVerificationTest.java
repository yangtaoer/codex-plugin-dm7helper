package io.dm7codex.plugin.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class ZzDm7CleanupVerificationTest {
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test void exactObjectsFromCurrentRunAreAbsent()throws Exception{
        Path manifest=Path.of(System.getProperty("dm7.integration.cleanup-manifest","target/dm7-integration-cleanup-manifest.json")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(manifest),"cleanup manifest was not produced");
        try{
            @SuppressWarnings("unchecked") Map<String,Object> document=JSON.readValue(manifest.toFile(),Map.class);
            @SuppressWarnings("unchecked") List<String> names=(List<String>)document.get("objectNames");
            assertEquals(3,names.size());assertEquals(3,names.stream().distinct().count());
            Map<String,String> env=System.getenv();String url=required(env,"DM7_IT_JDBC_URL"),user=required(env,"DM7_IT_USERNAME"),password=required(env,"DM7_IT_PASSWORD");
            Path driver=Path.of(required(env,"DM7_IT_DRIVER_JAR")).toAbsolutePath().normalize();
            try(var loader=new URLClassLoader(new java.net.URL[]{driver.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
                Driver jdbc=(Driver)Class.forName("dm7.jdbc.driver.Dm7Driver",true,loader).getDeclaredConstructor().newInstance();
                var properties=new Properties();properties.setProperty("user",user);properties.setProperty("password",password);
                try(var connection=jdbc.connect(url,properties)){
                    for(String name:names){assertTrue(name.matches("CODEX_DM7_IT_[A-Z0-9_]+"));try(var found=connection.getMetaData().getTables(null,null,name,new String[]{"TABLE"})){assertFalse(found.next(),"current-run cleanup verification failed for "+name);}}
                }finally{properties.clear();}
            }
        }finally{Files.deleteIfExists(manifest);}
    }
    private static String required(Map<String,String> env,String name){String value=env.get(name);if(value==null||value.isBlank())throw new IllegalStateException("integration environment is incomplete");return value;}
}
