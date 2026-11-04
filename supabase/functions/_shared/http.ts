// Edge Function 공용: JSON 응답과 호출한 사용자 확인.

import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

/**
 * 요청의 Bearer 토큰을 실은 supabase 클라이언트를 만든다. RLS가 이 사용자 기준으로 적용된다.
 * 토큰이 없으면 null. 토큰이 진짜인지는 [isSignedIn]으로 확인한다.
 */
export function userClient(req: Request): { supabase: SupabaseClient; token: string } | null {
  const token = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) return null;
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: `Bearer ${token}` } } },
  );
  return { supabase, token };
}

/** 로그인한(익명 포함) 사용자의 토큰인지 확인한다. */
export async function isSignedIn(supabase: SupabaseClient, token: string): Promise<boolean> {
  const { data, error } = await supabase.auth.getUser(token);
  return !error && !!data.user;
}
