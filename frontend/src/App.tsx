import { useEffect, useState } from "react";
import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
import AuthPanel from "./components/AuthPanel";
import StorageDashboard from "./components/StorageDashboard";
import {
  authToken,
  getPublicFolderResource,
  getPublicResource,
  me,
  publicFolderDownloadUrl,
  publicFileDownloadUrl,
  publicFileThumbnailUrl,
  publicRootFileDownloadUrl
} from "./api/client";
import type { AuthTokenResponse, FileItem, PublicResource, User } from "./types";

function LoginPage({ onAuthSuccess }: { onAuthSuccess: (user: User) => void }) {
  const navigate = useNavigate();
  return (
    <>
      <AuthPanel
        mode="login"
        onAuthSuccess={(authData: AuthTokenResponse) => {
          authToken.set(authData.token);
          onAuthSuccess(authData.user);
          navigate("/storage");
        }}
      />
      <p style={{ textAlign: "center" }}>
        Нет аккаунта? <Link to="/register">Зарегистрироваться</Link>
      </p>
    </>
  );
}

function RegisterPage({ onAuthSuccess }: { onAuthSuccess: (user: User) => void }) {
  const navigate = useNavigate();
  return (
    <>
      <AuthPanel
        mode="register"
        onAuthSuccess={(authData: AuthTokenResponse) => {
          authToken.set(authData.token);
          onAuthSuccess(authData.user);
          navigate("/storage");
        }}
      />
      <p style={{ textAlign: "center" }}>
        Уже есть аккаунт? <Link to="/login">Войти</Link>
      </p>
    </>
  );
}

function PublicFilePage() {
  const { token } = useParams();
  const [resource, setResource] = useState<PublicResource | null>(null);
  const [folderStack, setFolderStack] = useState<{ id: number | null; name: string }[]>([]);
  const [password, setPassword] = useState("");
  const [passwordRequired, setPasswordRequired] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!token) return;
    const savedPassword = sessionStorage.getItem(`public-password-${token}`) || "";
    setPassword(savedPassword);
    getPublicResource(token, savedPassword)
      .then((nextResource) => {
        setResource(nextResource);
        setFolderStack(nextResource.targetType === "folder" ? [{ id: nextResource.folderId, name: nextResource.name }] : []);
        setPasswordRequired(false);
      })
      .catch((e) => {
        const message = (e as Error).message;
        if (message.includes("password")) {
          setPasswordRequired(true);
          setError("");
          return;
        }
        setError(message);
      });
  }, [token]);

  async function submitPublicPassword(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token) return;
    try {
      setError("");
      const nextResource = await getPublicResource(token, password);
      sessionStorage.setItem(`public-password-${token}`, password);
      setResource(nextResource);
      setFolderStack(nextResource.targetType === "folder" ? [{ id: nextResource.folderId, name: nextResource.name }] : []);
      setPasswordRequired(false);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function openPublicFolder(folderId: number | null, name: string) {
    if (!token || folderId == null) return;
    try {
      setError("");
      const nextResource = await getPublicFolderResource(token, folderId, password);
      setResource(nextResource);
      setFolderStack((prev) => [...prev, { id: folderId, name }]);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function jumpToPublicFolder(index: number) {
    if (!token) return;
    const target = folderStack[index];
    if (!target) return;
    try {
      setError("");
      const nextResource = target.id == null ? await getPublicResource(token, password) : await getPublicFolderResource(token, target.id, password);
      setResource(nextResource);
      setFolderStack((prev) => prev.slice(0, index + 1));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function formatPublicBytes(bytes?: number | null): string {
    const value = bytes || 0;
    if (value < 1024) return `${value} B`;
    const units = ["KB", "MB", "GB", "TB"];
    let size = value / 1024;
    let index = 0;
    while (size >= 1024 && index < units.length - 1) {
      size /= 1024;
      index += 1;
    }
    return `${size.toFixed(size >= 10 ? 0 : 1)} ${units[index]}`;
  }

  function publicFileGroup(file?: FileItem): string {
    if (!file) return "other";
    if (file.fileGroup) return file.fileGroup;
    if (file.contentType.startsWith("image/")) return "photo";
    if (file.contentType.startsWith("video/")) return "video";
    if (file.contentType.startsWith("audio/")) return "audio";
    if (file.contentType.includes("pdf")) return "pdf";
    if (file.contentType.startsWith("text/")) return "document";
    return "other";
  }

  function publicBadge(file?: FileItem): string {
    if (!file) return "FILE";
    return (file.extension || file.name.split(".").pop() || "FILE").slice(0, 4).toUpperCase();
  }

  return (
    <section className="public-page">
      <header className="public-header">
        <div>
          <h2>{resource?.targetType === "folder" ? "Публичная папка" : "Публичный файл"}</h2>
          <p>{resource?.name || "Загрузка..."}</p>
        </div>
        <Link to="/login">Войти</Link>
      </header>
      {error && <p className="error-text">{error}</p>}
      {passwordRequired && (
        <form className="public-password-card" onSubmit={submitPublicPassword}>
          <h3>Ссылка защищена паролем</h3>
          <label>
            Пароль
            <input autoFocus type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
          </label>
          <button type="submit">Открыть</button>
        </form>
      )}
      {!error && !resource && !passwordRequired && <p className="loading-text">Загрузка...</p>}
      {resource && (
        <>
          {resource.targetType === "file" ? (
            <article className="public-single-file">
              {resource.files[0]?.hasThumbnail ? (
                <img src={publicFileThumbnailUrl(resource.token, resource.files[0].id, password)} alt={resource.name} />
              ) : (
                <div className={`file-art group-${publicFileGroup(resource.files[0])}`}>
                  <span>{publicBadge(resource.files[0])}</span>
                </div>
              )}
              <div>
                <h3>{resource.name}</h3>
                <p>{resource.contentType || "Неизвестный тип"} · {formatPublicBytes(resource.sizeBytes)}</p>
                <a className="public-download" href={publicRootFileDownloadUrl(resource.token, password)}>Скачать</a>
              </div>
            </article>
          ) : (
            <>
              <nav className="public-breadcrumbs">
                {folderStack.map((folder, index) => (
                  <button type="button" key={`${folder.id}-${index}`} onClick={() => jumpToPublicFolder(index)}>
                    {folder.name}
                  </button>
                ))}
              </nav>
              {resource.folderId != null && (
                <a className="public-download folder-download" href={publicFolderDownloadUrl(resource.token, resource.folderId, password)}>
                  Скачать папку ZIP
                </a>
              )}
              <div className="public-grid">
                {resource.folders.map((folder) => (
                  <article className="public-tile folder-tile" key={`folder-${folder.id}`} onClick={() => openPublicFolder(folder.id, folder.name)}>
                    <div className="folder-art" aria-hidden="true" />
                    <div className="tile-meta">
                      <h3>{folder.name}</h3>
                      <p>Папка</p>
                    </div>
                    <button type="button">Открыть</button>
                  </article>
                ))}
                {resource.files.map((file) => (
                  <article className="public-tile" key={`file-${file.id}`}>
                    {file.hasThumbnail ? (
                      <img className="public-thumb" src={publicFileThumbnailUrl(resource.token, file.id, password)} alt={file.name} />
                    ) : (
                      <div className={`file-art group-${publicFileGroup(file)}`}>
                        <span>{publicBadge(file)}</span>
                      </div>
                    )}
                    <div className="tile-meta">
                      <h3>{file.name}</h3>
                      <p>{formatPublicBytes(file.sizeBytes)} · {file.contentType}</p>
                    </div>
                    {token && <a href={publicFileDownloadUrl(token, file.id, password)}>Скачать</a>}
                  </article>
                ))}
                {resource.folders.length === 0 && resource.files.length === 0 && <p className="empty-public">Папка пуста.</p>}
              </div>
            </>
          )}
        </>
      )}
    </section>
  );
}

function ProfilePage({ user }: { user: User | null }) {
  if (!user) return <Navigate to="/login" replace />;
  return (
    <section className="auth-card">
      <h2>Профиль</h2>
      <p>Логин: {user.username}</p>
      <p>Email: {user.email}</p>
      <p>Занято: {user.usedSpace} байт</p>
      <p>Роль: {user.role}</p>
      <Link to="/storage">Вернуться в хранилище</Link>
    </section>
  );
}

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!authToken.get()) {
      setLoading(false);
      return;
    }
    me()
      .then((authUser) => setUser(authUser))
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
  }, []);

  function handleAuthSuccess(authUser: User) {
    setUser(authUser);
  }

  function handleLogout() {
    authToken.clear();
    setUser(null);
  }

  if (loading) {
    return <main className="page">Загрузка...</main>;
  }

  return (
    <BrowserRouter>
      <main className="page">
        <Routes>
          <Route path="/" element={<Navigate to={user ? "/storage" : "/login"} replace />} />
          <Route path="/login" element={<LoginPage onAuthSuccess={handleAuthSuccess} />} />
          <Route path="/register" element={<RegisterPage onAuthSuccess={handleAuthSuccess} />} />
          <Route path="/public/:token" element={<PublicFilePage />} />
          <Route path="/profile" element={<ProfilePage user={user} />} />
          <Route
            path="/storage"
            element={
              user ? (
                <StorageDashboard user={user} onLogout={handleLogout} />
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />
        </Routes>
      </main>
    </BrowserRouter>
  );
}
