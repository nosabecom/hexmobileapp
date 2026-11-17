package dev.nosa.hex;

import android.content.Context;
import androidx.javascriptengine.*;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs cached desktop transformations in an isolated engine without filesystem or network APIs. */
final class ScriptTransforms {
  private static JavaScriptSandbox sandbox;
  private static ListenableFuture<JavaScriptSandbox> connecting;

  private static synchronized JavaScriptSandbox engine(Context context) throws Exception {
    if (sandbox == null) {
      if (!JavaScriptSandbox.isSupported()) throw new IllegalStateException("Update Android System WebView for custom transformations");
      if (connecting == null) connecting = JavaScriptSandbox.createConnectedInstanceAsync(context.getApplicationContext());
      try { sandbox = connecting.get(5, TimeUnit.SECONDS); }
      catch (java.util.concurrent.ExecutionException e) { connecting = null; throw e; }
    }
    return sandbox;
  }

  static String apply(Context context, String bundle, List<String> chain, String text) throws Exception {
    JavaScriptSandbox engine = engine(context);
    if (!engine.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION)
        || !engine.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE))
      throw new IllegalStateException("Update Android System WebView for bounded custom transformations");
    IsolateStartupParameters parameters = new IsolateStartupParameters();
    parameters.setMaxHeapSizeBytes(64L * 1024 * 1024);
    try (JavaScriptIsolate isolate = engine.createIsolate(parameters)) {
      String script = bundle + "\n(() => { const input = " + JSONObject.quote(text)
          + "; const chain = " + new JSONArray(chain) + ";\n" + """
            const config = hexMobileConfig.default;
            const context = Object.freeze({application: null, browserHost: null, browserUrl: null, windowTitle: null});
            let text = input;
            for (const id of chain) {
              if (id === 'lowercase') text = text.toLowerCase();
              else if (id === 'spongebob-case') {
                let upper = false;
                text = [...text].map(c => {
                  if (!/\\p{L}/u.test(c)) return c;
                  const result = upper ? c.toUpperCase() : c.toLowerCase();
                  upper = !upper;
                  return result;
                }).join('');
              } else if (id === 'no-trailing-punctuation') {
                const body = text.trimEnd();
                text = body.replace(/[.,!?;:…。！？、，；：]+$/u, '') + text.slice(body.length);
              } else {
                const transformation = config.transformations?.[id];
                if (!transformation || typeof transformation.transform !== 'function') throw new Error('Missing transformation');
                text = transformation.transform(text, context);
              }
              if (typeof text !== 'string' || text.length > 131072) throw new Error('Invalid transformation output');
            }
            return JSON.stringify({text});
          })()
          """;
      String response = isolate.evaluateJavaScriptAsync(script).get(1500, TimeUnit.MILLISECONDS);
      return new JSONObject(response).getString("text");
    }
  }
}
