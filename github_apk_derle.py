"""
GameSub TV — Otomatik GitHub Bulut APK Derleyici ve İndirici (`github_apk_derle.py`).

Bilgisayarınızda Git, Java veya Android Studio kurulu olmasa bile:
  1. GitHub hesabınızla tek tıkla (Tarayıcı Cihaz Kodu ile) eşleşir,
  2. `gamesub-tv-apk` reposunu oluşturup proje dosyalarını doğrudan GitHub API ile yükler,
  3. GitHub Actions üzerinde APK derlemesini başlatıp canlı takip eder,
  4. Derlenen hazır `GameSub-TV.apk` dosyasını otomatik olarak bu klasöre (`e:\\proje\\çeviri\\temp\\mobil\\GameSub-TV.apk`) indirir!
"""
import base64
import io
import os
import sys
import time
import webbrowser
import zipfile
from pathlib import Path
import requests

# Official GitHub CLI OAuth Client ID for Device Flow
GITHUB_CLIENT_ID = "178c6fc778ccc68e1d6a"
BASE_DIR = Path(__file__).resolve().parent
TOKEN_FILE = BASE_DIR / ".github_token"
OUTPUT_APK = BASE_DIR / "GameSub-TV.apk"
REPO_NAME = "gamesub-tv-apk"

EXCLUDE_NAMES = {".github_token", "GameSub-TV.apk", "__pycache__", ".gradle", "build"}

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def get_or_request_github_token() -> str:
    if TOKEN_FILE.exists():
        saved = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if saved:
            # Verify token is still valid
            r = requests.get(
                "https://api.github.com/user",
                headers={"Authorization": f"token {saved}", "Accept": "application/vnd.github+json"},
                timeout=10,
            )
            if r.status_code == 200:
                print(f"[✓] GitHub hesabı doğrulandı: @{r.json().get('login')}")
                return saved

    print("\n" + "=" * 66)
    print("  🔗 GitHub Bulut APK Derleyici — Tek Seferlik Hesap Bağlantısı")
    print("=" * 66)

    resp = requests.post(
        "https://github.com/login/device/code",
        headers={"Accept": "application/json"},
        data={"client_id": GITHUB_CLIENT_ID, "scope": "repo workflow"},
        timeout=15,
    )
    resp.raise_for_status()
    data = resp.json()

    device_code = data["device_code"]
    user_code = data["user_code"]
    verification_uri = data["verification_uri"]
    interval = int(data.get("interval", 5))

    print(f"\n  👉 1. Tarayıcınızda şu adres açılıyor: {verification_uri}", flush=True)
    print(f"  👉 2. Ekrana şu 8 haneli kodu girip onaylayın:  [  {user_code}  ]\n", flush=True)
    print("=" * 66, flush=True)

    try:
        webbrowser.open(verification_uri)
    except Exception:
        pass

    print("⏳ Tarayıcıdan onaylamanız bekleniyor...", flush=True)
    while True:
        time.sleep(interval + 1)
        poll = requests.post(
            "https://github.com/login/oauth/access_token",
            headers={"Accept": "application/json"},
            data={
                "client_id": GITHUB_CLIENT_ID,
                "device_code": device_code,
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            },
            timeout=15,
        )
        p_data = poll.json()
        if "access_token" in p_data:
            token = p_data["access_token"]
            TOKEN_FILE.write_text(token, encoding="utf-8")
            print("[✓] GitHub yetkilendirmesi başarılı!")
            return token
        err = p_data.get("error", "")
        if err == "authorization_pending":
            continue
        elif err == "slow_down":
            interval += 5
        else:
            raise RuntimeError(f"GitHub yetkilendirme hatası: {p_data}")


def ensure_repo_exists(session: requests.Session) -> tuple[str, str]:
    user_resp = session.get("https://api.github.com/user", timeout=15)
    user_resp.raise_for_status()
    owner = user_resp.json()["login"]

    repo_resp = session.get(f"https://api.github.com/repos/{owner}/{REPO_NAME}", timeout=15)
    if repo_resp.status_code == 404:
        print(f"[+] GitHub üzerinde '{owner}/{REPO_NAME}' deposu oluşturuluyor...")
        create_resp = session.post(
            "https://api.github.com/user/repos",
            json={
                "name": REPO_NAME,
                "description": "GameSub TV - Android TV Subtitle Overlay APK Builder",
                "private": False,
                "auto_init": True,
            },
            timeout=20,
        )
        create_resp.raise_for_status()
        time.sleep(2)
    else:
        print(f"[✓] Mevcut GitHub deposu kullanılıyor: {owner}/{REPO_NAME}")

    return owner, REPO_NAME


def collect_project_files() -> list[tuple[str, bytes]]:
    files: list[tuple[str, bytes]] = []
    for root, dirs, filenames in os.walk(BASE_DIR):
        dirs[:] = [d for d in dirs if d not in EXCLUDE_NAMES]
        for fname in filenames:
            if fname in EXCLUDE_NAMES or fname.endswith(".pyc"):
                continue
            full_path = Path(root) / fname
            rel_path = full_path.relative_to(BASE_DIR).as_posix()
            files.append((rel_path, full_path.read_bytes()))
    return files


def upload_files_to_github(session: requests.Session, owner: str, repo: str) -> None:
    files = collect_project_files()
    print(f"[+] {len(files)} proje dosyası GitHub bulut sunucusuna yükleniyor...")

    # Ensure non-workflow files are committed first, and workflow file last so push triggers build cleanly
    files.sort(key=lambda item: (1 if item[0].startswith(".github/") else 0, item[0]))

    # Get default branch
    repo_info = session.get(f"https://api.github.com/repos/{owner}/{repo}", timeout=15).json()
    branch = repo_info.get("default_branch", "main")

    # Try batch Git Tree upload for speed
    ref_resp = session.get(f"https://api.github.com/repos/{owner}/{repo}/git/ref/heads/{branch}", timeout=15)
    if ref_resp.status_code == 200:
        base_commit_sha = ref_resp.json()["object"]["sha"]
        commit_resp = session.get(
            f"https://api.github.com/repos/{owner}/{repo}/git/commits/{base_commit_sha}", timeout=15
        )
        base_tree_sha = commit_resp.json()["tree"]["sha"]

        tree_items = []
        for rel_path, content in files:
            blob_resp = session.post(
                f"https://api.github.com/repos/{owner}/{repo}/git/blobs",
                json={"content": base64.b64encode(content).decode("ascii"), "encoding": "base64"},
                timeout=20,
            )
            blob_resp.raise_for_status()
            tree_items.append(
                {
                    "path": rel_path,
                    "mode": "100644",
                    "type": "blob",
                    "sha": blob_resp.json()["sha"],
                }
            )

        new_tree_resp = session.post(
            f"https://api.github.com/repos/{owner}/{repo}/git/trees",
            json={"base_tree": base_tree_sha, "tree": tree_items},
            timeout=25,
        )
        new_tree_resp.raise_for_status()
        new_tree_sha = new_tree_resp.json()["sha"]

        new_commit_resp = session.post(
            f"https://api.github.com/repos/{owner}/{repo}/git/commits",
            json={
                "message": "Build GameSub TV Android APK",
                "tree": new_tree_sha,
                "parents": [base_commit_sha],
            },
            timeout=20,
        )
        new_commit_resp.raise_for_status()
        new_commit_sha = new_commit_resp.json()["sha"]

        update_ref_resp = session.patch(
            f"https://api.github.com/repos/{owner}/{repo}/git/refs/heads/{branch}",
            json={"sha": new_commit_sha, "force": True},
            timeout=20,
        )
        update_ref_resp.raise_for_status()
        print("[✓] Tüm kaynak kodlar tek pakette GitHub'a aktarıldı!")
        return

    # Fallback: upload file by file via Contents API
    for rel_path, content in files:
        url = f"https://api.github.com/repos/{owner}/{repo}/contents/{rel_path}"
        existing = session.get(url, timeout=15)
        payload = {
            "message": f"Update {rel_path}",
            "content": base64.b64encode(content).decode("ascii"),
        }
        if existing.status_code == 200 and "sha" in existing.json():
            payload["sha"] = existing.json()["sha"]
        r = session.put(url, json=payload, timeout=20)
        r.raise_for_status()


def wait_and_download_apk(session: requests.Session, owner: str, repo: str) -> None:
    print("[+] GitHub Actions APK derleme işi başlatılıyor...")
    # Trigger workflow_dispatch explicitly as well
    session.post(
        f"https://api.github.com/repos/{owner}/{repo}/actions/workflows/build-apk.yml/dispatches",
        json={"ref": "main"},
        timeout=15,
    )

    time.sleep(5)
    run_id = None
    run_url = f"https://github.com/{owner}/{repo}/actions"

    for attempt in range(90):
        runs_resp = session.get(
            f"https://api.github.com/repos/{owner}/{repo}/actions/runs?per_page=5", timeout=15
        )
        if runs_resp.status_code == 200:
            runs = runs_resp.json().get("workflow_runs", [])
            if runs:
                latest = runs[0]
                run_id = latest["id"]
                status = latest["status"]
                conclusion = latest.get("conclusion")
                run_url = latest.get("html_url", run_url)
                print(
                    f"  ⏳ APK Derleme Durumu: {status.upper()} "
                    f"{f'({conclusion})' if conclusion else ''} — [{attempt * 6} sn]"
                )

                if status == "completed":
                    if conclusion == "success":
                        print("\n[✓] APK bulutta başarıyla derlendi! Bilgisayarınıza indiriliyor...")
                        break
                    else:
                        print(f"\n[!] Derleme sırasında hata oluştu. Detaylar: {run_url}")
                        return
        time.sleep(6)

    if not run_id:
        print(f"Derleme bağlantısı: {run_url}")
        return

    # Download artifact zip and extract GameSub-TV.apk
    art_resp = session.get(
        f"https://api.github.com/repos/{owner}/{repo}/actions/runs/{run_id}/artifacts", timeout=15
    )
    artifacts = art_resp.json().get("artifacts", [])
    if not artifacts:
        print(f"Artifact bulunamadı, GitHub Releases sayfasından indirebilirsiniz: https://github.com/{owner}/{repo}/releases")
        return

    download_url = artifacts[0]["archive_download_url"]
    zip_resp = session.get(download_url, timeout=60)
    zip_resp.raise_for_status()

    with zipfile.ZipFile(io.BytesIO(zip_resp.content)) as zf:
        for name in zf.namelist():
            if name.endswith(".apk"):
                OUTPUT_APK.write_bytes(zf.read(name))
                break

    print("\n" + "=" * 66)
    print("  🎉 BAŞARILI! ANDROID TV APK DOSYANIZ HAZIR!")
    print(f"  📂 Dosya Konumu : {OUTPUT_APK}")
    print(f"  🌐 Doğrudan İndirme (Releases): https://github.com/{owner}/{repo}/releases/latest")
    print("=" * 66)


def main():
    token = get_or_request_github_token()
    session = requests.Session()
    session.headers.update(
        {
            "Authorization": f"token {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "GameSub-TV-Builder",
        }
    )
    owner, repo = ensure_repo_exists(session)
    upload_files_to_github(session, owner, repo)
    wait_and_download_apk(session, owner, repo)


if __name__ == "__main__":
    main()
