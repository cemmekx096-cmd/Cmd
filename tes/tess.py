#!/usr/bin/env python3
"""
Test manual: Flow TurboVIP (LK21 extractor)

1. HTML punya <div id="video_player" data-hash="cdn.turboviplay.com/dataN/{hash}/{hash}.m3u8">
2. Brute-force ganti N (heuristik: coba N+2 duluan) untuk cari master multi-quality
   (indikasi: ada >1 baris #EXT-X-STREAM-INF)
3. Setiap nested master.m3u8 (turbosplayer.com) WAJIB di-fetch dengan header
   Referer: https://turboviplay.com/  -- kalau tidak, dapat konten decoy/iklan.
"""
import re
import requests

HEADERS_TEMPLATE = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://turboviplay.com/",
}


def find_multiquality_turbovip(original_url: str, session: requests.Session | None = None):
    """
    Brute-force varian 'dataN' pada URL turboviplay untuk cari master multi-quality.
    Heuristik: kalau HTML kasih 'data' (tanpa angka) -> coba 'data2' duluan.
               kalau HTML kasih 'dataN' -> coba 'data(N+2)' duluan.
    Kalau semua gagal, fallback ke original_url (kemungkinan cuma 1 quality).
    Return: (final_url, playlist_text_or_None)
    """
    sess = session or requests.Session()

    m = re.search(r"/(data)(\d*)/", original_url)
    if not m:
        print("[WARN] Pola 'dataN/' tidak ketemu di URL, langsung fetch original.")
        resp = sess.get(original_url, headers=HEADERS_TEMPLATE, timeout=15)
        return original_url, resp.text

    prefix_end = m.start(1)
    n = int(m.group(2)) if m.group(2) else 0

    # urutan coba: N+2 duluan (heuristik), lalu sisanya 0,1,2,3 (dedup)
    candidates_n = [n + 2] + [x for x in [0, 1, 2, 3] if x != n and x != n + 2]

    for cn in candidates_n:
        label = "data" if cn == 0 else f"data{cn}"
        cand_url = original_url[:prefix_end] + label + original_url[m.end(2):]
        print(f"\n[TRY] Cek {cand_url}")
        try:
            resp = sess.get(cand_url, headers=HEADERS_TEMPLATE, timeout=15)
        except requests.RequestException as e:
            print(f"[WARN] Gagal fetch: {e}")
            continue

        if resp.status_code != 200:
            print(f"[INFO] Status {resp.status_code}, skip.")
            continue

        stream_count = resp.text.count("#EXT-X-STREAM-INF")
        print(f"[INFO] Jumlah #EXT-X-STREAM-INF: {stream_count}")

        if stream_count > 1:
            print(f"[SUCCESS] Multi-quality ditemukan di {cand_url}")
            return cand_url, resp.text

    print("[INFO] Tidak ada varian multi-quality, fallback ke original_url.")
    try:
        resp = sess.get(original_url, headers=HEADERS_TEMPLATE, timeout=15)
        return original_url, resp.text
    except requests.RequestException as e:
        print(f"[ERROR] Fallback fetch juga gagal: {e}")
        return original_url, None


def parse_stream_variants(playlist_text: str, base_referer_master_url: str):
    """
    Parse baris #EXT-X-STREAM-INF + URL nested-nya dari master playlist.
    Return list of dict: {resolution, bandwidth, url}
    """
    variants = []
    lines = playlist_text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF"):
            res_m = re.search(r"RESOLUTION=(\d+x\d+)", line)
            bw_m = re.search(r"BANDWIDTH=(\d+)", line)
            nested_url = lines[i + 1].strip() if i + 1 < len(lines) else None
            if nested_url:
                variants.append({
                    "resolution": res_m.group(1) if res_m else "unknown",
                    "bandwidth": int(bw_m.group(1)) if bw_m else 0,
                    "url": nested_url,
                })
    return variants


def fetch_nested_playlist(nested_url: str, session: requests.Session | None = None):
    """Fetch nested master.m3u8 (turbosplayer.com dkk) WAJIB dengan Referer turboviplay.com."""
    sess = session or requests.Session()
    print(f"\n[INFO] GET nested: {nested_url}")
    resp = sess.get(nested_url, headers=HEADERS_TEMPLATE, timeout=15)
    print(f"[DEBUG] Status: {resp.status_code}")
    print(resp.text[:1500])
    return resp.text


if __name__ == "__main__":
    # Ganti dengan data-hash asli dari HTML turboviplay
    turbovip_url = "https://cdn.turboviplay.com/data1/6a6b60fd34fbf/6a6b60fd34fbf.m3u8"

    final_url, playlist_text = find_multiquality_turbovip(turbovip_url)

    if not playlist_text:
        print("[FAILED] Tidak dapat playlist sama sekali.")
    else:
        print(f"\n[RESULT] Master URL: {final_url}")
        print(playlist_text)

        variants = parse_stream_variants(playlist_text, final_url)
        print(f"\n[INFO] Ditemukan {len(variants)} varian quality:")
        for v in variants:
            print(f"  - {v['resolution']} ({v['bandwidth']} bps) -> {v['url']}")

        # Coba fetch salah satu varian (misal yang pertama) untuk verifikasi Referer
        if variants:
            fetch_nested_playlist(variants[0]["url"])
