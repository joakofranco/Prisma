package uy.edu.prisma.config;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RNF-10: enmascara contraseñas, tokens y encabezados Authorization/Bearer en cada línea de log
 * antes de escribirla, para que un log.info/debug/error que por error incluya ese dato sensible no
 * lo deje en texto plano -- no depende de que cada punto del código lo evite a mano. Se usa como
 * layout del appender CONSOLE en logback-spring.xml.
 */
public class MaskingPatternLayout extends PatternLayout {

  private static final String MASK = "***REDACTED***";

  private record MaskRule(Pattern pattern, String replacement) {}

  private static final List<MaskRule> RULES =
      List.of(
          new MaskRule(
              Pattern.compile("(?i)(\"password\"\\s*:\\s*\")[^\"]*(\")"), "$1" + MASK + "$2"),
          new MaskRule(Pattern.compile("(?i)(password=)[^&\\s,}]+"), "$1" + MASK),
          new MaskRule(
              Pattern.compile(
                  "(?i)(\"(?:token|accessToken|refreshToken|secret|clientSecret)\"\\s*:\\s*\")[^\"]*(\")"),
              "$1" + MASK + "$2"),
          new MaskRule(Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9\\-_.]+"), "$1" + MASK));

  @Override
  public String doLayout(ILoggingEvent event) {
    return mask(super.doLayout(event));
  }

  public static String mask(String input) {
    String result = input;
    for (MaskRule rule : RULES) {
      result = rule.pattern().matcher(result).replaceAll(rule.replacement());
    }
    return result;
  }
}
