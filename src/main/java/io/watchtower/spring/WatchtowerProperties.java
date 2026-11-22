package io.watchtower.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code watchtower.*} properties. No toString on purpose: the key must never be printed. */
@ConfigurationProperties("watchtower")
public class WatchtowerProperties {
  private boolean enabled = true;
  private String apiKey;
  private String endpoint;
  private String service;
  private String environment;
  private String release;
  private boolean captureUncaught = true;
  private Boolean remoteSampling;
  private Boolean codeOrigin;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { enabled = v; }
  public String getApiKey() { return apiKey; }
  public void setApiKey(String v) { apiKey = v; }
  public String getEndpoint() { return endpoint; }
  public void setEndpoint(String v) { endpoint = v; }
  public String getService() { return service; }
  public void setService(String v) { service = v; }
  public String getEnvironment() { return environment; }
  public void setEnvironment(String v) { environment = v; }
  public String getRelease() { return release; }
  public void setRelease(String v) { release = v; }
  public boolean isCaptureUncaught() { return captureUncaught; }
  public void setCaptureUncaught(boolean v) { captureUncaught = v; }
  /** Null: WT_REMOTE_SAMPLING decides (default off). */
  public Boolean getRemoteSampling() { return remoteSampling; }
  public void setRemoteSampling(Boolean v) { remoteSampling = v; }
  /** Null: WT_CODE_ORIGIN decides (default off). */
  public Boolean getCodeOrigin() { return codeOrigin; }
  public void setCodeOrigin(Boolean v) { codeOrigin = v; }
}
