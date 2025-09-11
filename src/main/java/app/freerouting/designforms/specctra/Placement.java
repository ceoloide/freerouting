package app.freerouting.designforms.specctra;

import app.freerouting.logger.FRLogger;
import java.io.IOException;

/**
 * Class for writing placement scopes from dsn-files.
 */
public class Placement extends ScopeKeyword {

  /**
   * Creates a new instance of Placement
   */
  public Placement() {
    super("placement");
  }

  @Override
  public boolean read_scope(ReadScopeParameter p_par) {
    Object next_token = null;
    for (; ; ) {
      Object prev_token = next_token;
      try {
        next_token = p_par.scanner.next_token();
      } catch (IOException e) {
        FRLogger.error("Placement.read_scope: IO error scanning file", e);
        return false;
      }
      if (next_token == null) {
        FRLogger.warn("Placement.read_scope: unexpected end of file at '" + p_par.scanner.get_scope_identifier() + "'");
        return false;
      }
      if (next_token == CLOSED_BRACKET) {
        // end of scope
        break;
      }
      boolean read_ok = true;
      if (prev_token == OPEN_BRACKET) {
        if (next_token == Keyword.PLACE_CONTROL) {
          read_ok = Keyword.PLACE_CONTROL.read_scope(p_par);
        } else if (next_token == Keyword.COMPONENT_SCOPE) {
          read_ok = Keyword.COMPONENT_SCOPE.read_scope(p_par);
        } else {
          skip_scope(p_par.scanner);
        }
      }
      if (!read_ok) {
        return false;
      }
    }
    return true;
  }

  public static void write_scope(WriteScopeParameter p_par) throws IOException {
    p_par.file.start_scope();
    p_par.file.write("placement");
    if (p_par.board.components.get_flip_style_rotate_first()) {
      p_par.file.new_line();
      p_par.file.write("(place_control (flip_style rotate_first))");
    }

    if (p_par.board.library.packages != null) {
      for (int i = 1; i <= p_par.board.library.packages.count(); ++i) {
        app.freerouting.designforms.specctra.Package.write_placement_scope(p_par, p_par.board.library.packages.get(i));
      }
    }
    p_par.file.end_scope();
  }
}