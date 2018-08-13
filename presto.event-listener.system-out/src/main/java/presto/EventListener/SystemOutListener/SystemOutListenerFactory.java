package presto.EventListener.SystemOutListener;
import com.facebook.presto.spi.eventlistener.EventListener;
import com.facebook.presto.spi.eventlistener.EventListenerFactory;

import java.util.Map;

import static java.util.Objects.requireNonNull;
public class SystemOutListenerFactory implements EventListenerFactory {
    @Override
    public String getName()
    {
        return "system-out";
    }

    @Override
    public EventListener create(Map<String, String> requiredConfig)
    {
        return new SystemOutListener(requiredConfig);
    }
}
