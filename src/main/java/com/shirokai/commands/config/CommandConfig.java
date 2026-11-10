package com.shirokai.commands.config;

import java.util.*;

public final class CommandConfig {
  public int configVersion = 1;
  public Settings settings = new Settings();
  public Messages messages = new Messages();
  public Map<String, Rule> commands = new LinkedHashMap<>();

  public static class Settings {
    public boolean opBypass = true,
        whitelistEnabled = false,
        autoRefreshTab = true,
        luckPermsIntegration = true,
        logBlockedCommands = true,
        silentBlockedCommands = false;
    public boolean guardInternalPlayerCommands = false;
    public String defaultMode = "allow";
  }

  public static class Messages {
    public String blocked = "§cVocê não tem permissão para usar este comando.",
        unknown = "§cComando desconhecido.",
        reload = "§aConfigurações recarregadas com sucesso.",
        aliasConflict = "§cEste alias já está sendo utilizado.";
  }

  public static class Rule {
    public String mode = "hide", alias, permission, message;
    public boolean security = false;

    public Rule() {}

    public Rule(String mode) {
      this.mode = mode;
    }
  }
}
