package app.freerouting.designforms.specctra;

import app.freerouting.logger.FRLogger;

import java.io.IOException;

/**
 * Keywords defining a scope object
 */
public class ScopeKeyword extends Keyword
{
  public ScopeKeyword(String p_name)
  {
    super(p_name);
  }

  /**
   * Skips the current scope while reading a dsn file. Returns false, if no legal scope was found.
   */
  public static boolean skip_scope(IJFlexScanner p_scanner)
  {
    int open_bracked_count = 1;
    while (open_bracked_count > 0)
    {
      p_scanner.yybegin(SpecctraDsnStreamReader.NAME);
      Object curr_token;
      try
      {
        curr_token = p_scanner.next_token();
      } catch (Exception e)
      {
        FRLogger.error("ScopeKeyword.skip_scope: Error while scanning file", e);
        return false;
      }
      if (curr_token == null)
      {
        return false; // end of file
      }
      if (curr_token == Keyword.OPEN_BRACKET)
      {
        ++open_bracked_count;
      }
      else if (curr_token == Keyword.CLOSED_BRACKET)
      {
        --open_bracked_count;
      }
    }
    return true;
  }

  /**
   * Reads the next scope of this keyword from dsn file.
   */
  public boolean read_scope(ReadScopeParameter p_par) {
    for (; ; ) {
      Object next_token;
      try {
        next_token = p_par.scanner.next_token();
      } catch (IOException e) {
        FRLogger.error("ScopeKeyword.read_scope: IO error scanning file", e);
        return false;
      }

      if (next_token == null) {
        // end of file, but scope was not closed.
        FRLogger.warn("ScopeKeyword.read_scope: unexpected end of file at '" + p_par.scanner.get_scope_identifier() + "'");
        return false;
      }

      if (next_token == CLOSED_BRACKET) {
        // end of scope
        return true;
      }

      if (next_token == OPEN_BRACKET) {
        // This is the start of a child scope.
        Object keyword_token;
        try {
          keyword_token = p_par.scanner.next_token();
        } catch (IOException e) {
          FRLogger.error("ScopeKeyword.read_scope: IO error scanning file", e);
          return false;
        }

        if (keyword_token instanceof ScopeKeyword) {
          ScopeKeyword next_scope = (ScopeKeyword) keyword_token;
          if (!next_scope.read_scope(p_par)) {
            return false;
          }
        } else {
          // Not a scope keyword we have a specific reader for, skip it.
          skip_scope(p_par.scanner);
        }
      }
      // If it's not a bracket, it's some other content within the scope that this generic reader doesn't understand.
      // The original code implicitly ignored this, so we will too.
    }
  }
}