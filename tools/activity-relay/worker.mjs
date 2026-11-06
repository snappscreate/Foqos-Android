/**
 * Optional Gemini relay for Foqos's activity test page.
 *
 * Cloudflare Worker secrets:
 *   GEMINI_API_KEY  (required; Gemini never reaches the Android APK)
 *   LAB_TOKEN       (required; long, random, private per-device token)
 * Optional vars:
 *   GEMINI_MODEL    (defaults to gemini-3.5-flash)
 *   INPUT_USD_PER_M, OUTPUT_USD_PER_M, USD_JPY
 *
 * This deliberately has no CORS and no persistent image/video storage.
 * For a personal development experiment, don't publish a token in source control.
 */
export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("POST only", {status:405});
    if (!env.LAB_TOKEN || !env.GEMINI_API_KEY) return new Response("Relay not configured", {status:503});
    const supplied = request.headers.get("Authorization") ?? "";
    if (supplied !== "Bearer " + env.LAB_TOKEN)
      return new Response("Unauthorized", {status:401});
    const size = Number(request.headers.get("content-length") || "0");
    if (size > 18_000_000) return new Response("Payload too large", {status:413});
    let payload;
    try { payload = await request.json(); }
    catch { return new Response("Invalid JSON", {status:400}); }
    const activity = String(payload.activity ?? "").trim().slice(0, 160);
    if (!activity) return new Response("Missing activity", {status:400});
    const mode = payload.mode;
    if (mode !== "photos" && mode !== "video")
      return new Response("Invalid mode", {status:400});

    const parts = [{
      text: `あなたは動画・画像の行動評価者。利用者が行動「${activity}」を本当に行ったか、
      提供された時系列の画像または動画から確認して。単にそれらしい姿勢が1枚映った
      だけでは「一致」にしない。撮影範囲外、見えない動作、読書内容など、
      証拠が足りない場合は必ず「判断不能」。見える事実と推測を区別。
      JSONのみで status（「一致」「不一致」「判断不能」のいずれか）、
      reason（短い理由）、evidence（可視的な根拠）を返して。個人を特定しない。 `
    }];
    if (mode === "video") {
      if (!payload.video || payload.video.mimeType !== "video/mp4" ||
          typeof payload.video.data !== "string")
        return new Response("Invalid video", {status:400});
      parts.push({inlineData: {
        mimeType: "video/mp4", data: payload.video.data
      }});
    } else {
      if (!Array.isArray(payload.frames) || payload.frames.length < 1 ||
          payload.frames.length > 40)
        return new Response("Invalid frame count", {status:400});
      for (const frame of payload.frames) {
        if (frame.mimeType !== "image/jpeg" || typeof frame.data !== "string")
          return new Response("Invalid frame", {status:400});
        parts.push({text: `撮影時刻: ${Number(frame.timestampMs) || 0} ms`});
        parts.push({inlineData: {mimeType:"image/jpeg", data:frame.data}});
      }
    }
    const model = env.GEMINI_MODEL || "gemini-3.5-flash";
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`;
    const reply = await fetch(url, {
      method: "POST",
      headers: {"Content-Type":"application/json", "x-goog-api-key":env.GEMINI_API_KEY},
      body: JSON.stringify({
        contents:[{role:"user", parts}],
        generationConfig:{
          temperature:0.1,
          responseMimeType:"application/json",
          responseSchema:{
            type:"OBJECT",
            properties:{
              status:{type:"STRING", enum:["一致","不一致","判断不能"]},
              reason:{type:"STRING"},
              evidence:{type:"STRING"},
            },
            required:["status","reason","evidence"],
          },
        },
      }),
    });
    if (!reply.ok) {
      // Do not forward raw Google error payloads containing sensitive request metadata.
      return new Response("Gemini upstream error: " + reply.status, {status:502});
    }
    const gemini = await reply.json();
    const answer = gemini.candidates?.[0]?.content?.parts?.map(p=>p.text || "").join("") || "{}";
    let judged;
    try { judged = JSON.parse(answer); }
    catch { return new Response("Invalid Gemini JSON", {status:502}); }
    const usage = gemini.usageMetadata || {};
    const inputTokens = usage.promptTokenCount || 0;
    const outputTokens = (usage.candidatesTokenCount || 0) +
      (usage.thoughtsTokenCount || 0);
    const result = {
      status:["一致","不一致","判断不能"].includes(judged.status)
        ? judged.status : "判断不能",
      reason:String(judged.reason || "判断根拠が不足"),
      evidence:String(judged.evidence || ""),
      inputTokens,outputTokens,
    };
    if (env.INPUT_USD_PER_M && env.OUTPUT_USD_PER_M && env.USD_JPY) {
      const usd = (inputTokens * Number(env.INPUT_USD_PER_M) +
        outputTokens * Number(env.OUTPUT_USD_PER_M)) / 1_000_000;
      result.estimatedYen = Math.round(usd * Number(env.USD_JPY) * 10000) / 10000;
    }
    return Response.json(result, {headers:{"Cache-Control":"no-store"}});
  }
};
