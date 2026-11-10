package com.shirokai.commands.config;

import java.util.*;

public final class CommandConfig {
  public int configVersion = 1;
  public Settings settings = new Settings();
  public Messages messages = new Messages();
  public Map<String, Rule> commands = new LinkedHashMap<>();
  public List<TabFilter> tabFilters = new ArrayList<>();
  public Map<String, ArgumentAlias> argumentAliases = new LinkedHashMap<>();

  public static class ArgumentAlias {
    public String alias;
    public String target;
    public boolean extendExisting = false;
    public String permission;
    public Map<String, List<String>> suggestions = new LinkedHashMap<>();
  }

  /** Cosmetic suggestion filters; argumentIndex counts from one after the command path. */
  public static class TabFilter {
    public String command;
    public int argumentIndex;
    public boolean onlinePlayersOnly = false;
    public boolean hideAll = false;
    public List<String> hidePrefixes = new ArrayList<>();
  }

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
