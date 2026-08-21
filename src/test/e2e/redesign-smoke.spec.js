const { test, expect } = require("@playwright/test");

const teacherEmail = process.env.E2E_TEACHER_EMAIL || "teacher.visual@example.test";
const teacherPassword = process.env.E2E_TEACHER_PASSWORD || "change-me-now";

async function assertNoHorizontalOverflow(page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
}

test("public landing and authentication remain responsive", async ({ page }) => {
  for (const width of [320, 390, 768, 1280, 1440]) {
    await page.setViewportSize({ width, height: width < 800 ? 844 : 900 });
    await page.goto("/");
    await expect(page.getByRole("heading", { name: /Спокойное рабочее пространство/ })).toBeVisible();
    await assertNoHorizontalOverflow(page);
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/register");
  await expect(page.getByRole("heading", { name: "Регистрация" })).toBeVisible();
  await expect(page.getByLabel(/Имя и фамилия/)).not.toBeFocused();
  await assertNoHorizontalOverflow(page);
});

test("teacher calendar switches periods without a full navigation", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(teacherEmail);
  await page.getByLabel("Пароль").fill(teacherPassword);
  await page.getByRole("button", { name: "Войти" }).click();
  await expect(page).toHaveURL(/\/teacher/);
  await expect(page.getByRole("heading", { name: /Добрый день/ })).toBeVisible();

  await page.setViewportSize({ width: 1280, height: 900 });
  const navigationCount = await page.evaluate(() => performance.getEntriesByType("navigation").length);
  const month = page.getByRole("link", { name: "Месяц" });
  await month.click();
  await expect(page).toHaveURL(/view=month/);
  await expect(page.locator(".calendar-workspace.is-month")).toBeVisible();
  expect(await page.evaluate(() => performance.getEntriesByType("navigation").length)).toBe(navigationCount);

  const nextMonth = page.getByRole("link", { name: "Следующий месяц" });
  await nextMonth.click();
  await expect(page).toHaveURL(/month=/);
  await assertNoHorizontalOverflow(page);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(page.locator(".calendar-workspace.is-month")).toBeVisible();
  await expect(page.locator(".mobile-nav")).toBeVisible();
  await assertNoHorizontalOverflow(page);
});

test("lesson dialog and account menu close through standard interactions", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(teacherEmail);
  await page.getByLabel("Пароль").fill(teacherPassword);
  await page.getByRole("button", { name: "Войти" }).click();
  await expect(page).toHaveURL(/\/teacher/);

  const add = page.locator("#new-lesson");
  await add.locator("summary").click();
  await expect(add).toHaveAttribute("open", "");
  await page.getByRole("heading", { name: /Добрый день/ }).click();
  await expect(add).not.toHaveAttribute("open", "");

  await page.locator("summary.account-summary").click();
  await expect(page.locator(".account-menu")).toHaveAttribute("open", "");
  await page.keyboard.press("Escape");
  await expect(page.locator(".account-menu")).not.toHaveAttribute("open", "");
});
