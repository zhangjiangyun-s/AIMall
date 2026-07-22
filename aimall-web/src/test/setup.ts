import { afterEach, beforeEach } from "vitest";
import { config } from "@vue/test-utils";

config.global.stubs = {
  RouterLink: { template: "<a><slot /></a>" },
  RouterView: { template: "<div />" },
  Transition: false,
  Teleport: true,
};

beforeEach(() => {
  localStorage.clear();
  document.body.innerHTML = "";
});

afterEach(() => {
  document.body.className = "";
});
