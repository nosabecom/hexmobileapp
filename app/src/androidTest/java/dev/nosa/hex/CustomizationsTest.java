package dev.nosa.hex;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import org.junit.*;

public class CustomizationsTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

  @Test public void scriptsAreIsolatedAndKeepInputDataLiteral() throws Exception {
    String bundle = "var hexMobileConfig = {default:{transformations:{test:{transform:text=>"
        + "{if(typeof fetch!=='undefined'||typeof process!=='undefined')throw Error(); return text+'!';}}}}};";
    String input = "INPUT_TEXT TRANSFORM_CHAIN \" \\ \n😀";
    assertEquals(input + "!", ScriptTransforms.apply(context, bundle, List.of("test"), input));
  }

  @Test public void failedAndRunawayScriptsCannotBlockLaterDictations() throws Exception {
    String prefix = "var hexMobileConfig = {default:{transformations:{test:{transform:text=>";
    try {
      ScriptTransforms.apply(context, prefix + "{throw Error();}}}}};", List.of("test"), "original");
      fail("Expected script failure");
    } catch (java.util.concurrent.ExecutionException expected) {}
    try {
      ScriptTransforms.apply(context, prefix + "{while(true){} }}}}};", List.of("test"), "original");
      fail("Expected script timeout");
    } catch (java.util.concurrent.TimeoutException expected) {}
    assertEquals("ok", ScriptTransforms.apply(context, prefix + "text}}}};", List.of("test"), "ok"));
  }

  @Test public void actualNewEraScriptsCorrectNamesAndInsertPunctuationOffline() throws Exception {
    RemoteTranscriber.syncCustomizations(context);
    assertTrue("Configure the server and export NewEra settings first", Customizations.hasSnapshot(context));
    String bundle = Customizations.snapshot(context).getString("script");
    List<String> chain = List.of("nosa-names", "inline-insert");
    assertEquals("Nosa Omokaro.", ScriptTransforms.apply(context, bundle, chain, "No sir Omokaru."));
    assertEquals("hello world", ScriptTransforms.apply(context, bundle, chain, "Insert, Hello world."));
    assertEquals("Nosa is here", ScriptTransforms.apply(context, bundle, chain, "Insert, Nosa is here."));
    assertEquals("No, sir.", ScriptTransforms.apply(context, bundle, chain, "Literal no sir."));
    assertEquals("No sir, I don't agree.", ScriptTransforms.apply(context, bundle, chain, "No sir, I don't agree."));
    assertEquals("Nosakhare Omokaro.", ScriptTransforms.apply(context, bundle, chain, "Nosa khare omo karo."));
  }
}
