import axios from "axios";

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "",
  timeout: 10000,
});

function clearAuthState() {
  localStorage.removeItem("token");
  localStorage.removeItem("userInfo");
  localStorage.removeItem("aimall_login_session_id");
  localStorage.removeItem("aimall_login_session_token");
}

http.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem("token");
    if (token) {
      config.headers.token = token;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

http.interceptors.response.use(
  (response) => {
    const data = response.data;
    if (data && data.code !== 0 && data.message) {
      const msg = data.message as string;
      if (msg.includes("token") || msg.includes("登录")) {
        clearAuthState();
        window.location.href = "/login";
      }
    }
    return response;
  },
  (error) => {
    const status = error.response?.status as number | undefined;
    const message = String(error.response?.data?.message || error.message || "网络连接失败，请稍后重试");
    if (status === 401) {
      clearAuthState();
      if (window.location.pathname !== "/login") window.location.href = "/login";
    }
    const apiError = new Error(message);
    (apiError as Error & { status?: number }).status = status;
    return Promise.reject(apiError);
  }
);

export default http;
