package presto.EventListener.SystemOutListener;
import com.facebook.presto.spi.eventlistener.EventListener;
import com.facebook.presto.spi.eventlistener.QueryCompletedEvent;
import com.facebook.presto.spi.eventlistener.QueryCreatedEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.airlift.log.Logger;

import java.util.Map;

import static java.util.Objects.requireNonNull;

public class SystemOutListener implements EventListener {

    private static final Logger log = Logger.get(SystemOutListener.class);


    public SystemOutListener(Map<String, String> requiredConfig) {
    }

    @Override
    public void queryCreated(QueryCreatedEvent queryCreatedEvent) {
        Gson obj = new GsonBuilder().disableHtmlEscaping().create();
        String json = obj.toJson(queryCreatedEvent);
        System.out.println("queryCreated::" + json);
    }

    @Override
    public void queryCompleted(QueryCompletedEvent queryCompletedEvent) {
        Gson obj = new GsonBuilder().disableHtmlEscaping().create();
        String json = obj.toJson(queryCompletedEvent);
        System.out.println("queryCompleted::" + json);
    }
}
