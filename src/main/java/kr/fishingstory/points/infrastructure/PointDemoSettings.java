package kr.fishingstory.points.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class PointDemoSettings {
	private final boolean enabled;
	public PointDemoSettings(Environment environment, @Value("${app.dev.seed-points:false}") boolean requested) {
		// Even an accidentally enabled production property must never expose demo rows.
		this.enabled = requested && environment.acceptsProfiles(Profiles.of("dev & !prod"));
	}
	public boolean enabled() { return enabled; }
}
