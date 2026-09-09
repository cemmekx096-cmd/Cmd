#!/usr/bin/env python3
"""
Test manual: Step 1-2 dari flow P2P (LK21 extractor)

Bahan1 -> parse host+id dari URL path -> POST videonode.de/api.php -> embedUrl (Bahan2)

Catatan: bagian devtools-detection & window.top redirect di JS aslinya cuma
proteksi sisi browser, gak ngaruh kalau kita hit endpoint-nya langsung
lewat requests (bukan browser).
"""
import re
import requests

BASE_URL = "https://videonode.de"

HEADERS_TEMPLATE = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "*/*",
    "X-Requested-With": "XMLHttpRequest",
    "Content-Type": "application/x-www-form-urlencoded",
}


def parse_bahan1(bahan1_url: str):
    """
    https://videonode.de/iframe3/p2p/K_QU-jLa9vhoHzXlq-Sc8w
                          ^host^   ^--------id--------^
    """
    parts = bahan1_url.rstrip("/").split("/")
    vid = parts[-1]
    host = parts[-2]
    return host, vid


def get_embed_url(bahan1_url: str, session: requests.Session | None = None):
    sess = session or requests.Session()
    host, vid = parse_bahan1(bahan1_url)
    print(f"[INFO] Parsed -> host={host}  id={vid}")

    api_url = f"{BASE_URL}/api.php"
    headers = dict(HEADERS_TEMPLATE)
    headers["Referer"] = bahan1_url

    # Opsional: GET bahan1_url dulu biar dapat cookie session (kalau situs butuh)
    try:
        pre = sess.get(bahan1_url, headers={"User-Agent": HEADERS_TEMPLATE["User-Agent"]}, timeout=15)
        print(f"[DEBUG] GET bahan1 status: {pre.status_code}, cookies: {dict(sess.cookies)}")
    except requests.RequestException as e:
        print(f"[WARN] GET bahan1 gagal: {e}")

    body = f"host={host}&id={vid}"
    print(f"[INFO] POST {api_url}  body={body}")

    resp = sess.post(api_url, headers=headers, data=body, timeout=15)
    print(f"[DEBUG] Status: {resp.status_code}")
    print(f"[DEBUG] Raw body: {resp.text[:1000]}")

    try:
        j = resp.json()
    except ValueError:
        print("[ERROR] Respons bukan JSON valid.")
        return None

    embed_url = j.get("embedUrl")
    if embed_url:
        print(f"[SUCCESS] embedUrl: {embed_url}")
    else:
        print(f"[FAILED] Tidak ada 'embedUrl' di respons: {j}")
    return embed_url


def inspect_embed_url(embed_url: str, referer: str, session: requests.Session | None = None):
    """Step 3 (investigasi): fetch embedUrl, simpan full HTML + cari beberapa pola sekaligus."""
    sess = session or requests.Session()
    headers = dict(HEADERS_TEMPLATE)
    headers.pop("Content-Type", None)  # GET, bukan form post
    headers["Referer"] = referer
    headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    print(f"\n[INFO] GET {embed_url}")
    resp = sess.get(embed_url, headers=headers, timeout=15, allow_redirects=True)
    print(f"[DEBUG] Status: {resp.status_code}")
    print(f"[DEBUG] Final URL (setelah redirect kalau ada): {resp.url}")
    print(f"[DEBUG] Content-Type: {resp.headers.get('Content-Type')}")
    print(f"[DEBUG] Panjang body: {len(resp.text)} char")

    out_file = "playcdn_raw.html"
    with open(out_file, "w", encoding="utf-8") as f:
        f.write(resp.text)
    print(f"[INFO] Full HTML disimpan ke {out_file} (buka manual kalau perlu)")

    import re
    patterns = {
        "var data": r"var\s+data\s*=\s*(\{.*?\});",
        "token key": r"[\"']token[\"']\s*:\s*[\"']([^\"']+)[\"']",
        "id key": r"[\"']id[\"']\s*:\s*[\"']([^\"']+)[\"']",
        "ajax/php endpoint": r"[\"'](/[a-zA-Z0-9_\-]+\.php[^\"']*)[\"']",
        "fetch(": r"fetch\(([^)]+)\)",
        "XMLHttpRequest usage": r"xhr\.open\([^)]+\)",
        "atob( / base64 decode": r"atob\(([^)]+)\)",
        "vstr reference": r"vstr[^\n;]{0,200}",
    }

    print("\n[SCAN] Mencari pola-pola penting di HTML:")
    for label, pat in patterns.items():
        matches = re.findall(pat, resp.text, re.DOTALL)
        if matches:
            print(f"  [{label}] ditemukan {len(matches)}x, contoh: {matches[0][:200]}")
        else:
            print(f"  [{label}] tidak ditemukan")

    return resp


def extract_var_data(html: str):
    """Cari `var data = {...};` di HTML dan parse sebagai JSON."""
    import json
    m = re.search(r"var\s+data\s*=\s*(\{.*?\});", html, re.DOTALL)
    if not m:
        print("[FAILED] Pola 'var data = {...}' tidak ditemukan di HTML.")
        return None
    raw = m.group(1)
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"[ERROR] Gagal parse JSON var data: {e}\nRaw: {raw[:300]}")
        return None
    print(f"[SUCCESS] var data ditemukan: id={data.get('id')} token(len)={len(data.get('token',''))}")
    return data


def verify_and_get_fileurl(token: str, referer: str, session: requests.Session | None = None):
    """Step 4: POST token ke playcdn.de/verify.php -> {status, fileUrl}."""
    sess = session or requests.Session()
    url = "https://playcdn.de/verify.php"
    headers = {
        "User-Agent": HEADERS_TEMPLATE["User-Agent"],
        "Content-Type": "application/json",
        "Accept": "application/json, text/plain, */*",
        "Referer": referer,
        "Origin": "https://playcdn.de",
    }
    payload = {"token": token, "is_ios": False}

    print(f"\n[INFO] POST {url}")
    resp = sess.post(url, headers=headers, json=payload, timeout=15)
    print(f"[DEBUG] Status: {resp.status_code}")
    print(f"[DEBUG] Raw body: {resp.text[:1000]}")

    try:
        j = resp.json()
    except ValueError:
        print("[ERROR] Respons verify.php bukan JSON valid.")
        return None

    if j.get("status") == "success" and j.get("fileUrl"):
        print(f"[SUCCESS] fileUrl (m3u8): {j['fileUrl']}")
        return j["fileUrl"]
    else:
        print(f"[FAILED] verify.php gagal: {j}")
        return None


if __name__ == "__main__":
    # Ganti dengan link bahan1 asli dari halaman anime
    bahan1 = "https://videonode.de/iframe3/turbovip/aDx92Y6Ms9Q7vhofplhWRyZjKtBRzN51pm6KiMkfRxk"
    embed_url = get_embed_url(bahan1)

    if embed_url:
        # Coba beberapa varian URL — mungkin server butuh query tambahan
        # (turbovip's embedUrl sudah include ?ok=1 & id= eksplisit, p2p tidak)
        candidates = [embed_url]
        if "?" not in embed_url:
            candidates.append(embed_url + "?ok=1")
        else:
            candidates.append(embed_url + "&ok=1")

        resp = None
        data = None
        for cand in candidates:
            print(f"\n[TRY] Mencoba URL: {cand}")
            resp = inspect_embed_url(cand, referer=bahan1)
            data = extract_var_data(resp.text)
            if data:
                embed_url = cand
                break
            else:
                print(f"[INFO] Gagal dengan {cand}, lanjut coba varian lain kalau ada...")

        if data and data.get("token"):
            verify_and_get_fileurl(data["token"], referer=embed_url)
