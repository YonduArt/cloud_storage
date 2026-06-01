import { useState } from "react";
import { login, register } from "../api/client";
import type { AuthTokenResponse } from "../types";

const initialRegister = { displayName: "", username: "", email: "", password: "" };
const initialLogin = { login: "", password: "" };

export default function AuthPanel({
  mode = "login",
  onAuthSuccess
}: {
  mode?: "login" | "register";
  onAuthSuccess: (data: AuthTokenResponse) => void;
}) {
  const isRegister = mode === "register";
  const [registerForm, setRegisterForm] = useState(initialRegister);
  const [loginForm, setLoginForm] = useState(initialLogin);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setLoading(true);
    try {
      const response = isRegister ? await register(registerForm) : await login(loginForm);
      onAuthSuccess(response);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="auth-card">
      <h1>Облачный диск</h1>
      <p className="subtitle">Хранилище файлов для дипломного проекта</p>
      <form onSubmit={handleSubmit} className="auth-form">
        {isRegister && (
          <>
            <label>
              Имя
              <input
                value={registerForm.displayName}
                onChange={(e) => setRegisterForm((prev) => ({ ...prev, displayName: e.target.value }))}
                required
              />
            </label>
            <label>
              Логин
              <input
                value={registerForm.username}
                onChange={(e) => setRegisterForm((prev) => ({ ...prev, username: e.target.value }))}
                required
              />
            </label>
          </>
        )}
        <label>
          {isRegister ? "Email" : "Email или логин"}
          <input
            type={isRegister ? "email" : "text"}
            value={isRegister ? registerForm.email : loginForm.login}
            onChange={(e) =>
              isRegister
                ? setRegisterForm((prev) => ({ ...prev, email: e.target.value }))
                : setLoginForm((prev) => ({ ...prev, login: e.target.value }))
            }
            required
          />
        </label>
        <label>
          Пароль
          <input
            type="password"
            value={isRegister ? registerForm.password : loginForm.password}
            onChange={(e) =>
              isRegister
                ? setRegisterForm((prev) => ({ ...prev, password: e.target.value }))
                : setLoginForm((prev) => ({ ...prev, password: e.target.value }))
            }
            required
          />
        </label>
        {error && <p className="error-text">{error}</p>}
        <button disabled={loading} type="submit">
          {loading ? "Подождите..." : isRegister ? "Создать аккаунт" : "Войти"}
        </button>
      </form>
    </section>
  );
}
