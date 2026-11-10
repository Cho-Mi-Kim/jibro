// Keep presentation latency separate from the demo's data and validation rules.
export async function waitForDemoResponse() {
    await new Promise((resolve) => setTimeout(resolve, 180));
}
