// RFC 9457 오류 형식. 원본: docs/architecture.md 4.1절.

export interface FieldErrorDetail {
  path: string
  message: string
}

export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  detail?: string
  instance?: string
  errors?: FieldErrorDetail[]
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`)
    this.status = problem.status
    this.problem = problem
  }

  /** errors[].path로 필드별 메시지를 찾을 때 쓴다. */
  fieldMessage(path: string): string | undefined {
    return this.problem.errors?.find((e) => e.path === path)?.message
  }
}
