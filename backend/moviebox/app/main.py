from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import socket
import threading
import time
import unicodedata
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlencode

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field


BASE_URL = "https://api.inmoviebox.com"
APP_ID = "4U01pxRu278GqCZKY9"
SIGN_KEY = base64.b64decode("76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O")
USER_AGENT = "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)"
REGION = os.getenv("MOVIEBOX_REGION", "BR").upper()
SP_CODE = os.getenv("MOVIEBOX_SP_CODE", "404")
TOKEN_SECRET = os.getenv("VELA_TOKEN_SECRET", "troque-esta-chave-no-casaos").encode()
API_KEY = os.getenv("VELA_API_KEY", "")


class MediaRequest(BaseModel):
    mediaType: str = Field(pattern="^(movie|tv)$")
    title: str = Field(min_length=1, max_length=300)
    year: int | None = None
    tmdbId: str | None = None
    imdbId: str | None = None
    season: int | None = None
    episode: int | None = None


@dataclass
class CacheItem:
    expires: float
    value: Any


class TtlCache:
    def __init__(self) -> None:
        self._values: dict[str, CacheItem] = {}
        self._lock = threading.Lock()

    def get(self, key: str) -> Any | None:
        with self._lock:
            item = self._values.get(key)
            if not item or item.expires < time.time():
                self._values.pop(key, None)
                return None
            return item.value

    def put(self, key: str, value: Any, ttl: int = 300) -> Any:
        with self._lock:
            self._values[key] = CacheItem(time.time() + ttl, value)
        return value


class MovieBoxClient:
    def __init__(self) -> None:
        self.http = httpx.Client(
            timeout=httpx.Timeout(30, connect=12),
            follow_redirects=True,
            trust_env=False,
            transport=httpx.HTTPTransport(retries=2),
        )
        self.device_id = hashlib.md5(f"vela-{socket.gethostname()}".encode()).hexdigest()
        self._guest_token: str | None = None
        self._token_lock = threading.Lock()
        self.cache = TtlCache()

    @staticmethod
    def _timezone() -> str:
        return {
            "BR": "America/Sao_Paulo",
            "US": "America/New_York",
            "RU": "Europe/Moscow",
        }.get(REGION, "Asia/Kolkata")

    def _client_info(self) -> str:
        return json.dumps({
            "package_name": "com.movieboxpro.android",
            "version_name": "16.2.1",
            "version_code": 16210,
            "os": "android",
            "os_version": "12",
            "install_ch": "googleplay",
            "device_id": self.device_id,
            "install_store": "googleplay",
            "gaid": "",
            "brand": "google",
            "model": "Pixel 6",
            "system_language": "en",
            "net": "wifi",
            "region": REGION,
            "timezone": self._timezone(),
            "sp_code": SP_CODE,
        }, separators=(",", ":"))

    def _signed_headers(self, method: str, path: str, params: dict[str, Any], body: bytes) -> dict[str, str]:
        timestamp = str(int(time.time() * 1000))
        accept = "application/json"
        content_type = "application/json;charset=UTF-8"
        sorted_query = "&".join(f"{key}={params[key]}" for key in sorted(params))
        canonical_path = path + (f"?{sorted_query}" if sorted_query else "")
        body_length = str(len(body)) if body else ""
        body_hash = hashlib.md5(body[:102400]).hexdigest() if body else ""
        canonical = f"{method}\n{accept}\n{content_type}\n{body_length}\n{timestamp}\n{body_hash}\n{canonical_path}"
        signature = base64.b64encode(hmac.new(SIGN_KEY, canonical.encode(), hashlib.md5).digest()).decode()
        language = "pt" if REGION == "BR" else "ru" if REGION == "RU" else "en"
        return {
            "User-Agent": USER_AGENT,
            "Accept": accept,
            "Accept-Language": "pt-BR,pt;q=0.9,en;q=0.8" if REGION == "BR" else "en-US,en;q=0.9",
            "Content-Type": content_type,
            "X-Sign-Version": "2.0",
            "appid": APP_ID,
            "region": REGION,
            "lang": language,
            "os": "android",
            "X-Timestamp": timestamp,
            "Referer": f"{BASE_URL}/",
            "X-Client-Token": f"{timestamp},{hashlib.md5(timestamp[::-1].encode()).hexdigest()}",
            "x-tr-signature": f"{timestamp}|2|{signature}",
            "X-Play-Mode": "2",
            "X-Client-Info": self._client_info(),
            "X-Client-Status": "1",
        }

    def _bootstrap_token(self, force: bool = False) -> str:
        with self._token_lock:
            if self._guest_token and not force:
                return self._guest_token
            path = "/wefeed-mobile-bff/tab-operating"
            params = {"host": "api.inmoviebox.com", "page": 1, "pageSize": 24, "tabId": 1}
            response = self.http.get(
                f"{BASE_URL}{path}", params=params,
                headers=self._signed_headers("GET", path, params, b""),
            )
            response.raise_for_status()
            raw = response.headers.get("x-user", "")
            if raw.startswith("{"):
                raw = json.loads(raw).get("token", "")
            if not raw:
                raise RuntimeError("MovieBox não forneceu o token de visitante")
            self._guest_token = raw
            return raw

    def request(self, method: str, path: str, params: dict[str, Any] | None = None,
                payload: dict[str, Any] | None = None, retry: bool = True) -> dict[str, Any]:
        parameters = {"host": "api.inmoviebox.com", **(params or {})}
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode() if payload is not None else b""
        token = self._bootstrap_token()
        headers = self._signed_headers(method, path, parameters, body)
        headers["Authorization"] = f"Bearer {token}"
        response = self.http.request(method, f"{BASE_URL}{path}", params=parameters, content=body or None, headers=headers)
        if retry and (response.status_code == 401 or "JWT token has expired" in response.text):
            self._bootstrap_token(force=True)
            return self.request(method, path, params, payload, retry=False)
        response.raise_for_status()
        data = response.json()
        if data.get("code") not in (None, 0, 200):
            raise RuntimeError(data.get("message") or data.get("reason") or f"MovieBox code {data.get('code')}")
        return data

    def search(self, title: str) -> list[dict[str, Any]]:
        key = f"search:{title.casefold()}"
        cached = self.cache.get(key)
        if cached is not None:
            return cached
        data = self.request("POST", "/wefeed-mobile-bff/subject-api/search",
                            payload={"keyword": title, "page": 1, "pageSize": 20})
        block = data.get("data") or {}
        items = block if isinstance(block, list) else block.get("items") or block.get("list") or block.get("subjects") or []
        return self.cache.put(key, items)

    def detail(self, subject_id: str) -> dict[str, Any]:
        key = f"detail:{subject_id}"
        cached = self.cache.get(key)
        if cached is not None:
            return cached
        data = self.request("GET", "/wefeed-mobile-bff/subject-api/get", {"subjectId": subject_id})
        return self.cache.put(key, data.get("data") or {})


client = MovieBoxClient()
media_http = httpx.Client(
    timeout=httpx.Timeout(60, connect=12),
    follow_redirects=True,
    trust_env=False,
    transport=httpx.HTTPTransport(retries=2),
)
app = FastAPI(title="Vela MovieBox Source", version="1.0.0")


def require_api_key(x_vela_key: str | None = Header(default=None)) -> None:
    if API_KEY and not hmac.compare_digest(API_KEY, x_vela_key or ""):
        raise HTTPException(401, "Chave da API inválida")


def normalize(value: str) -> str:
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode().casefold()
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def item_year(item: dict[str, Any]) -> int | None:
    raw = str(item.get("releaseDate") or item.get("year") or "")
    found = re.search(r"(?:19|20)\d{2}", raw)
    return int(found.group()) if found else None


def choose_match(items: list[dict[str, Any]], request: MediaRequest) -> dict[str, Any]:
    target = normalize(request.title)
    wanted_type = 1 if request.mediaType == "movie" else 2

    def score(item: dict[str, Any]) -> int:
        title = normalize(str(item.get("title") or item.get("name") or ""))
        subject_type = int(item.get("subjectType") or item.get("type") or 0)
        value = 0
        if title == target:
            value += 100
        elif target and (target in title or title in target):
            value += 45
        else:
            value += len(set(target.split()) & set(title.split())) * 8
        if subject_type == wanted_type:
            value += 35
        if request.year and item_year(item):
            difference = abs(request.year - item_year(item))
            value += 25 if difference == 0 else 8 if difference == 1 else -min(difference, 15)
        if item.get("hasResource"):
            value += 10
        return value

    if not items:
        raise HTTPException(404, "Título não encontrado no servidor")
    match = max(items, key=score)
    if score(match) < 45:
        raise HTTPException(404, "Nenhum resultado confiável para este título")
    return match


def language_score(label: str) -> int:
    text = normalize(label)
    if any(x in text for x in ("portuguese", "portugues", "brazil", "pt br", "ptbr", "dublado")):
        return 100
    if "original" in text:
        return 50
    if any(x in text for x in ("english", "ingles")):
        return 30
    return 10


def display_language(label: str) -> str:
    text = normalize(label)
    if any(x in text for x in ("portuguese", "portugues", "brazil", "pt br", "ptbr")):
        return "PT-BR Dublado"
    if "original" in text:
        return "Áudio original"
    if "english" in text:
        return "Inglês"
    return label.strip() or "Opção alternativa"


def encode_option(payload: dict[str, Any]) -> str:
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()
    content = base64.urlsafe_b64encode(raw).rstrip(b"=")
    signature = hmac.new(TOKEN_SECRET, content, hashlib.sha256).digest()[:16]
    return (content + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")).decode()


def decode_option(token: str) -> dict[str, Any]:
    try:
        content, encoded_signature = token.encode().split(b".", 1)
        signature = base64.urlsafe_b64decode(encoded_signature + b"=" * (-len(encoded_signature) % 4))
        expected = hmac.new(TOKEN_SECRET, content, hashlib.sha256).digest()[:16]
        if not hmac.compare_digest(signature, expected):
            raise ValueError("signature")
        raw = base64.urlsafe_b64decode(content + b"=" * (-len(content) % 4))
        return json.loads(raw)
    except Exception as exc:
        raise HTTPException(400, "Opção inválida ou expirada") from exc


def detect_stream_quality(stream: dict[str, Any]) -> str:
    """Read a real resolution from metadata, URL, or the CDN filename."""
    candidates = " ".join(str(stream.get(key) or "") for key in (
        "quality", "resolution", "height", "name", "fileName", "url"
    ))
    match = re.search(r"(?<!\d)(2160|1440|1080|720|576|540|480|360|240|144)\s*[pP]?", candidates)
    if match:
        return f"{match.group(1)}p"

    url = str(stream.get("url") or "")
    if not url:
        return "Automática"
    cache_key = f"quality:{hashlib.sha256(url.encode()).hexdigest()}"
    cached = client.cache.get(cache_key)
    if cached:
        return str(cached)

    headers = {"User-Agent": USER_AGENT, "Accept": "*/*", "Accept-Encoding": "identity"}
    cookie = str(stream.get("signCookie") or "")
    if cookie:
        headers["Cookie"] = cookie
    try:
        response = media_http.head(url, headers=headers)
        disposition = response.headers.get("content-disposition", "")
        match = re.search(r"(?<!\d)(2160|1440|1080|720|576|540|480|360|240|144)\s*[pP]", disposition)
        quality = f"{match.group(1)}p" if match else "Automática"
    except Exception:
        quality = "Automática"
    return client.cache.put(cache_key, quality, ttl=1200)


def available_streams(option: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any]]:
    subject_id = str(option["s"])
    resource_id = option.get("r")
    movie = bool(option.get("movie"))
    season = int(option.get("se") or 1)
    episode = int(option.get("ep") or 1)
    params: dict[str, Any] = {"subjectId": subject_id, "host": "api6.aoneroom.com"}
    if resource_id:
        params["resourceId"] = resource_id
    if not movie:
        params.update({"se": season, "ep": episode})

    response = client.request("GET", "/wefeed-mobile-bff/subject-api/play-info", params)
    data = response.get("data") or {}
    streams = data.get("streamList") or data.get("streams") or []
    valid = [item for item in streams if item.get("url") and not is_dummy(str(item.get("url")))]
    if not valid:
        valid = [
            item for item in ugc_streams(subject_id, season, episode, movie)
            if item.get("url") and not is_dummy(str(item.get("url")))
        ]

    if not valid:
        for item in streams:
            cookie = str(item.get("signCookie") or "")
            found = re.search(r"urlprefix=([^:;]+)", cookie)
            if not found:
                continue
            try:
                encoded = found.group(1)
                decoded = base64.b64decode(encoded + "=" * (-len(encoded) % 4)).decode()
                rebuilt = decoded + ("index.m3u8" if decoded.endswith("/") else "/index.m3u8")
                rebuilt_item = dict(item)
                rebuilt_item["url"] = rebuilt
                valid.append(rebuilt_item)
            except Exception:
                pass
    return valid, response, data


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True, "service": "vela-moviebox", "region": REGION}


@app.post("/v1/options", dependencies=[Depends(require_api_key)])
def options(request: MediaRequest) -> dict[str, Any]:
    match = choose_match(client.search(request.title), request)
    subject_id = str(match.get("subjectId") or match.get("id"))
    detail = client.detail(subject_id)
    raw_options: list[tuple[str, str, str | None]] = []

    for dub in detail.get("dubs") or []:
        dub_id = str(dub.get("subjectId") or "")
        if dub_id:
            dub_label = str(dub.get("lanName") or dub.get("lan") or "Dublagem")
            # Recursos de uma dublagem pertencem ao subjectId da própria dublagem.
            dub_detail = client.detail(dub_id) if dub_id != subject_id else detail
            dub_detectors = dub_detail.get("resourceDetectors") or []
            if dub_detectors:
                for detector in dub_detectors:
                    raw_options.append((dub_label, dub_id, str(detector.get("resourceId") or "") or None))
            else:
                raw_options.append((dub_label, dub_id, None))
    for detector in detail.get("resourceDetectors") or []:
        resource_id = str(detector.get("resourceId") or "")
        if resource_id:
            label = str(detector.get("name") or detector.get("language") or detector.get("uploadBy") or "Áudio original")
            raw_options.append((label, subject_id, resource_id))
    if not raw_options:
        raw_options.append((str(detail.get("language") or match.get("language") or "Áudio original"), subject_id, None))

    seen: set[tuple[str, str | None]] = set()
    result_by_language: dict[str, dict[str, Any]] = {}
    for label, option_subject, resource_id in sorted(raw_options, key=lambda x: -language_score(x[0])):
        identity = (option_subject, resource_id)
        if identity in seen:
            continue
        seen.add(identity)
        friendly = display_language(label)
        base_option = {
            "s": option_subject, "m": subject_id, "r": resource_id,
            "movie": request.mediaType == "movie", "se": request.season or 1,
            "ep": request.episode or 1, "label": friendly,
        }
        streams, _, _ = available_streams(base_option)
        if not streams:
            continue
        quality_entries = []
        quality_seen = set()
        for stream_index, stream in enumerate(streams):
            quality = detect_stream_quality(stream)
            identity_quality = (quality, str(stream.get("url") or ""))
            if identity_quality in quality_seen:
                continue
            quality_seen.add(identity_quality)
            quality_entries.append((stream_index, quality))
        quality_entries.sort(
            key=lambda entry: int(re.search(r"\d+", entry[1]).group()) if re.search(r"\d+", entry[1]) else 0,
            reverse=True,
        )
        # Uma unica fonte por idioma. A melhor qualidade inicia automaticamente;
        # as demais sao exibidas no seletor Qualidade do player.
        if quality_entries:
            best_index, best_quality = quality_entries[0]
            token = encode_option({**base_option, "stream": best_index, "quality": best_quality})
            best_height_match = re.search(r"\d+", best_quality)
            candidate = {
                "id": token,
                "name": f"Servidor Vela • {friendly}",
                "language": friendly,
                "quality": best_quality,
                "qualities": [
                    {
                        "id": encode_option({**base_option, "stream": stream_index, "quality": quality}),
                        "label": quality,
                    }
                    for stream_index, quality in quality_entries
                ],
                "_score": (
                    int(best_height_match.group()) if best_height_match else 0,
                    len(quality_entries),
                ),
            }
            current = result_by_language.get(friendly)
            if current is None or candidate["_score"] > current["_score"]:
                result_by_language[friendly] = candidate

    result = sorted(
        result_by_language.values(),
        key=lambda item: (-language_score(str(item["language"])), str(item["language"])),
    )
    for item in result:
        item.pop("_score", None)

    return {
        "match": {"subjectId": subject_id, "title": match.get("title"), "year": item_year(match)},
        "options": result[:20],
    }


def stream_score(stream: dict[str, Any]) -> int:
    url = str(stream.get("url") or "").lower()
    codec_name = str(stream.get("codecName") or stream.get("codec") or "").lower()
    hevc = any(codec in f"{url} {codec_name}" for codec in ("h265", "x265", "hev1", "hevc"))
    score = 100 if ".m3u8" in url else 90 if ".mpd" in url else 80 if ".mp4" in url else 30
    if hevc:
        score -= 35
    quality = str(stream.get("quality") or stream.get("resolution") or "")
    if "1080" in quality:
        score += 12
    elif "720" in quality:
        score += 8
    return score


def ugc_streams(subject_id: str, season: int, episode: int, movie: bool) -> list[dict[str, Any]]:
    """Fallback usado pelo AnyFilm quando play-info retorna apenas armadilhas ou nada."""
    try:
        first = client.request(
            "GET", "/wefeed-mobile-bff/ugc/video/first-by-subject", {"subjectId": subject_id}
        ).get("data") or {}
        collection = first.get("belongToCollection") or {}
        collection_id = str(collection.get("collectionId") or "")
        ugc_video_id = str(first.get("ugcVideoId") or "")

        if collection_id and not movie:
            target = f"S{season}EP{episode}"
            target_alt = f"S{season}E{episode}"
            for page in range(1, 9):
                block = client.request(
                    "GET", "/wefeed-mobile-bff/ugc/collection/download-list",
                    {"collectionId": collection_id, "page": page, "perPage": 50},
                ).get("data") or {}
                items = block.get("items") or []
                for item in items:
                    searchable = re.sub(r"[^A-Z0-9]", "", f"{item.get('epTitle', '')} {item.get('title', '')}".upper())
                    if target in searchable or target_alt in searchable:
                        return item.get("resources") or []
                pager = block.get("pager") or {}
                if not pager.get("hasMore") or not items:
                    break

        if ugc_video_id:
            block = client.request(
                "GET", "/wefeed-mobile-bff/ugc/video/download", {"ugcVideoId": ugc_video_id}
            ).get("data") or {}
            resources = []
            for item in block.get("items") or []:
                resources.extend(item.get("resources") or [])
            return resources
    except Exception:
        return []
    return []


def is_dummy(url: str) -> bool:
    return any(marker in url for marker in ("macdn.aoneroom.com", "please_update", "update_app", "b164fbfb43"))


def captions(subject_id: str, resource_id: str | None, season: int, episode: int,
             movie: bool) -> list[dict[str, Any]]:
    if not resource_id:
        return []
    params: dict[str, Any] = {"subjectId": subject_id, "resourceId": resource_id}
    if not movie:
        params.update({"se": season, "ep": episode})
    try:
        data = client.request("GET", "/wefeed-mobile-bff/subject-api/get-ext-captions", params).get("data") or {}
        return data.get("extCaptions") or data.get("list") or []
    except Exception:
        return []


def resolve_upstream(token: str) -> dict[str, Any]:
    option = decode_option(token)
    subject_id = str(option["s"])
    resource_id = option.get("r")
    movie = bool(option.get("movie"))
    season = int(option.get("se") or 1)
    episode = int(option.get("ep") or 1)
    valid, response, data = available_streams(option)
    if not valid:
        raise HTTPException(404, "Nenhum stream válido encontrado para esta opção")

    requested_index = option.get("stream")
    try:
        stream_index = int(requested_index)
    except (TypeError, ValueError):
        stream_index = -1
    best = valid[stream_index] if 0 <= stream_index < len(valid) else max(valid, key=stream_score)
    url = str(best["url"])
    cookie = str(best.get("signCookie") or response.get("signCookie") or data.get("signCookie") or "")
    subtitle_items = list(data.get("subTitleList") or [])
    subtitle_items.extend(captions(subject_id, resource_id, season, episode, movie))
    subtitles = []
    used_urls = set()
    for item in sorted(subtitle_items, key=lambda x: -language_score(str(x.get("lanName") or x.get("lan") or ""))):
        sub_url = str(item.get("url") or "")
        if not sub_url or sub_url in used_urls:
            continue
        used_urls.add(sub_url)
        label = str(item.get("lanName") or item.get("lan") or "Legenda")
        subtitles.append({"label": display_language(label), "file": sub_url})

    mime = "application/x-mpegURL" if ".m3u8" in url.lower() else "application/dash+xml" if ".mpd" in url.lower() else "video/mp4" if ".mp4" in url.lower() else None
    headers = {"User-Agent": USER_AGENT, "Referer": f"{BASE_URL}/", "Accept": "*/*"}
    if cookie:
        headers["Cookie"] = cookie
    quality_options = []
    quality_seen = set()
    for index, stream in enumerate(valid):
        quality = detect_stream_quality(stream)
        stream_url = str(stream.get("url") or "")
        identity = (quality, stream_url)
        if not stream_url or identity in quality_seen:
            continue
        quality_seen.add(identity)
        variant_token = encode_option({**option, "stream": index, "quality": quality})
        variant_mime = (
            "application/x-mpegURL" if ".m3u8" in stream_url.lower()
            else "application/dash+xml" if ".mpd" in stream_url.lower()
            else "video/mp4" if ".mp4" in stream_url.lower()
            else None
        )
        height_match = re.search(r"\d+", quality)
        quality_options.append({
            "token": variant_token,
            "label": quality,
            "height": int(height_match.group()) if height_match else 0,
            "type": variant_mime,
        })
    quality_options.sort(key=lambda item: int(item.get("height") or 0), reverse=True)

    return {
        "source": url,
        "type": mime,
        "headers": headers,
        "subtitles": subtitles,
        "extraBuffering": ".m3u8" in url.lower() or ".mpd" in url.lower(),
        "language": option.get("label"),
        "_qualities": quality_options,
    }


@app.get("/v1/resolve/{token}", dependencies=[Depends(require_api_key)])
def resolve(token: str, request: Request) -> dict[str, Any]:
    """Resolve metadata, while keeping the CDN URL and cookies on the server."""
    result = resolve_upstream(token)
    client.cache.put(f"stream:{token}", result, ttl=1200)
    public_base = str(request.base_url).rstrip("/")
    quality_options = result.get("_qualities") or []
    return {
        **{key: value for key, value in result.items() if key != "_qualities"},
        "source": f"{public_base}/v1/stream/{token}",
        "qualities": [
            {
                "label": item["label"],
                "height": item["height"],
                "type": item.get("type"),
                "source": f"{public_base}/v1/stream/{item['token']}",
                "headers": {"Accept": "*/*"},
            }
            for item in quality_options
        ],
        # The phone talks only to Vela. Upstream Cookie/Referer stay server-side.
        "headers": {"Accept": "*/*"},
    }


@app.get("/v1/stream/{token}")
def stream(token: str) -> StreamingResponse:
    """Proxy MovieBox media while hiding client byte-range requests from broken CDNs."""
    resolved = client.cache.get(f"stream:{token}")
    if resolved is None:
        resolved = resolve_upstream(token)
        client.cache.put(f"stream:{token}", resolved, ttl=1200)

    upstream_headers = dict(resolved.get("headers") or {})
    # hakunaymatata advertises byte ranges but responds with 429/428 whenever
    # Range is present. Media3 correctly handles a 200 response by skipping
    # bytes locally, so the proxy deliberately requests the complete object.
    upstream_headers.pop("Range", None)
    if "hakunaymatata.com" in str(resolved["source"]).lower():
        # This CDN returns 429 when the MovieBox API is sent as Referer, while
        # the same signed URL is valid without it.
        upstream_headers.pop("Referer", None)
    upstream_headers["Accept-Encoding"] = "identity"

    upstream_request = media_http.build_request(
        "GET",
        str(resolved["source"]),
        headers=upstream_headers,
    )
    upstream = media_http.send(upstream_request, stream=True)
    if upstream.status_code >= 400:
        status = upstream.status_code
        upstream.close()
        raise HTTPException(status, f"CDN recusou a reprodução (HTTP {status})")

    response_headers = {}
    for name in ("content-length", "etag", "last-modified"):
        value = upstream.headers.get(name)
        if value:
            response_headers[name] = value

    def body_iterator():
        try:
            yield from upstream.iter_raw(chunk_size=256 * 1024)
        finally:
            upstream.close()

    return StreamingResponse(
        body_iterator(),
        status_code=upstream.status_code,
        media_type=upstream.headers.get("content-type") or resolved.get("type") or "application/octet-stream",
        headers=response_headers,
    )
