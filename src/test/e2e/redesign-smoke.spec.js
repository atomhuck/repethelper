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

  await page.setViewportSize({ width: 1024, height: 830 });
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "Войти в аккаунт" })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollHeight <= window.innerHeight + 1)).toBe(true);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect.poll(() => page.locator(".auth-card").evaluate(card => card.getBoundingClientRect().top < window.innerHeight * .55)).toBe(true);
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
  const selectedDay = page.locator(".month-calendar .calendar-day:not(.muted)").nth(3);
  await selectedDay.click();
  await expect(page.locator(".mobile-day-agenda")).toContainText("Выбранный день");
  await expect(page.locator(".month-calendar .calendar-day.selected")).toHaveCount(1);
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
  await expect(page.locator("html")).toHaveAttribute("data-ui-ready", "true");
  await add.locator("summary").click();
  await expect(add).toHaveAttribute("open", "");
  await page.getByRole("heading", { name: /Добрый день/ }).click();
  await expect(add).not.toHaveAttribute("open", "");

  await page.locator("summary.account-summary").click();
  await expect(page.locator(".account-menu")).toHaveAttribute("open", "");
  await page.keyboard.press("Escape");
  await expect(page.locator(".account-menu")).not.toHaveAttribute("open", "");
});

test("mobile lesson header keeps its content aligned with the page", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/login");
  await page.getByLabel("Email").fill(teacherEmail);
  await page.getByLabel("Пароль").fill(teacherPassword);
  await page.getByRole("button", { name: "Войти" }).click();
  await expect(page).toHaveURL(/\/teacher/);

  const lessonLinks = page.locator('a[href^="/lessons/"]');
  test.skip((await lessonLinks.count()) === 0, "The smoke fixture does not create lessons");
  const lessonHref = await lessonLinks.first().getAttribute("href");
  await page.goto(lessonHref);
  await expect(page).toHaveURL(/\/lessons\//);
  await expect.poll(() => page.evaluate(() => {
    const hero = document.querySelector(".lesson-hero");
    const content = hero?.firstElementChild;
    return !!hero && !!content && Math.abs(content.getBoundingClientRect().left - hero.getBoundingClientRect().left) <= 1;
  })).toBe(true);
  await assertNoHorizontalOverflow(page);
});
