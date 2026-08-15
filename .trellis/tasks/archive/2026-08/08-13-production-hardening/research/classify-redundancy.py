"""Classify the zero-reference candidates from scan-redundancy.py by Spring stereotype.

"No direct reference" proves nothing on its own: Spring instantiates controllers,
configurations, health indicators and the boot class by classpath scanning, so those are
live despite zero call sites. Only a plain class that nothing constructs and no framework
picks up is a real deletion candidate.
"""
import os
import re
import json
import subprocess

scan = json.loads(subprocess.run(
    ["python", os.path.join(".trellis", "tasks", "08-13-production-hardening",
                            "research", "scan-redundancy.py")],
    capture_output=True, text=True, encoding="utf-8", check=True).stdout)

# Markers that mean "the framework owns this class's lifecycle".
FRAMEWORK = [
    ("@SpringBootApplication", "boot entry point"),
    ("@RestController", "MVC endpoint"),
    ("@ControllerAdvice", "MVC advice"),
    ("@Controller", "MVC endpoint"),
    ("@Configuration", "bean definitions"),
    ("@ConfigurationProperties", "config binding"),
    ("@Entity", "JPA entity"),
    ("@Embeddable", "JPA embeddable"),
    ("HealthIndicator", "actuator health"),
    ("CommandLineRunner", "startup runner"),
    ("ApplicationRunner", "startup runner"),
    ("@Component", "scanned component"),
    ("@Service", "scanned service"),
    ("@Repository", "scanned repository"),
    ("@RabbitListener", "MQ listener"),
    ("@EventListener", "event listener"),
    ("@Scheduled", "scheduled job"),
    ("implements Filter", "servlet filter"),
    ("extends OncePerRequestFilter", "servlet filter"),
]

framework_owned, plain = [], []
for item in scan["zeroReferences"]:
    try:
        with open(item["file"], encoding="utf-8", errors="ignore") as fh:
            src = fh.read()
    except OSError:
        continue
    reasons = [why for marker, why in FRAMEWORK if marker in src]
    # A bean-factory method elsewhere may also own it; @Bean-returning types were already
    # counted as references, so reaching here means nothing constructs it either.
    record = {"name": item["name"], "file": item["file"], "reasons": sorted(set(reasons))}
    (framework_owned if reasons else plain).append(record)

print(json.dumps({
    "totalMainClasses": scan["totalMainClasses"],
    "zeroRefCount": len(scan["zeroReferences"]),
    "frameworkOwned": len(framework_owned),
    "frameworkOwnedSample": framework_owned,
    "plainUnreferenced": plain,
    "testOnlyReferences": scan["testOnlyReferences"],
}, ensure_ascii=False, indent=2))
