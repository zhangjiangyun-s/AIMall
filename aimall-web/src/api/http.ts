import axios from "axios";

const http = axios.create({
  baseURL: "http://localhost:8080",
  timeout: 10000,
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error("API Error:", error.message);
    return Promise.reject(error);
  }
);

export default http;
