type IconProps = {
  className?: string
  active?: boolean
}

function Svg({ className, active, d }: IconProps & { d: string }) {
  return (
    <svg
      className={className}
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <path
        d={d}
        stroke="currentColor"
        strokeWidth={active ? 2.5 : 1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export function IconFormats(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M7 3.5h10A1.5 1.5 0 0118.5 5v14A1.5 1.5 0 0117 20.5H7A1.5 1.5 0 015.5 19V5A1.5 1.5 0 017 3.5zM8.5 8h7M8.5 12h7M8.5 16h4"
    />
  )
}

export function IconSchedules(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M7 4v2.5M17 4v2.5M4.5 8.5h15M6 6.5h12A1.5 1.5 0 0119.5 8v11A1.5 1.5 0 0118 20.5H6A1.5 1.5 0 014.5 19V8A1.5 1.5 0 016 6.5z"
    />
  )
}

export function IconPrint(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M7 9V5.5h10V9M7 15.5H5.5A1.5 1.5 0 014 14V10.5A1.5 1.5 0 015.5 9h13A1.5 1.5 0 0120 10.5V14a1.5 1.5 0 01-1.5 1.5H17M7 13.5h10v6H7v-6z"
    />
  )
}

export function IconMore(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M5 12h.01M12 12h.01M19 12h.01M6 12a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0z"
    />
  )
}

export function IconBack(props: IconProps) {
  return <Svg {...props} d="M15 5l-7 7 7 7" />
}

export function IconKebab(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M12 6.5a1 1 0 110-2 1 1 0 010 2zM12 13a1 1 0 110-2 1 1 0 010 2zM12 19.5a1 1 0 110-2 1 1 0 010 2z"
    />
  )
}

export function IconClose(props: IconProps) {
  return <Svg {...props} d="M6 6l12 12M18 6L6 18" />
}

export function IconChevron(props: IconProps) {
  return <Svg {...props} d="M9 6l6 6-6 6" />
}

export function IconChevronUp(props: IconProps) {
  return <Svg {...props} d="M6 15l6-6 6 6" />
}

export function IconChevronDown(props: IconProps) {
  return <Svg {...props} d="M6 9l6 6 6-6" />
}

export function IconPlus(props: IconProps) {
  return <Svg {...props} d="M12 5v14M5 12h14" />
}

export function IconTrash(props: IconProps) {
  return <Svg {...props} d="M5 7h14M10 7V5a1 1 0 011-1h2a1 1 0 011 1v2m-8 0l1 12a1 1 0 001 1h6a1 1 0 001-1l1-12" />
}

export function IconGear(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M12 15a3 3 0 100-6 3 3 0 000 6zM19 12a7 7 0 00-.12-1.29l1.85-1.44-1.5-2.6-2.19.72a7 7 0 00-2.24-1.29L14.5 4h-3l-.32 2.1a7 7 0 00-2.24 1.29l-2.19-.72-1.5 2.6L6.1 10.7a7 7 0 000 2.58l-1.85 1.44 1.5 2.6 2.19-.72a7 7 0 002.24 1.29L9.5 20h3l.32-2.1a7 7 0 002.24-1.29l2.19.72 1.5-2.6-1.85-1.44c.08-.42.12-.85.12-1.29z"
    />
  )
}

export function IconWarning(props: IconProps) {
  return <Svg {...props} d="M12 3l10 18H2L12 3zM12 10v4M12 17h.01" />
}

/** 위젯 카탈로그 아이콘. `descriptor.icon`이 모르는 값이면 IconWidgetGeneric으로 대체한다(widget.ts 주석). */
export function IconWidgetLetter(props: IconProps) {
  return <Svg {...props} d="M4 6h16v12H4V6zM4 6l8 7 8-7" />
}

export function IconWidgetChart(props: IconProps) {
  return <Svg {...props} d="M5 19V5M5 19h15M9 16V9l3 3 3-5 3 4" />
}

export function IconWidgetWeather({ className }: IconProps) {
  return (
    <svg className={className} width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="9" cy="8" r="3" stroke="currentColor" strokeWidth="1.5" />
      <path
        d="M6.5 18h10a3.5 3.5 0 000-7 4.5 4.5 0 00-8.6-1.6"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export function IconWidgetText(props: IconProps) {
  return <Svg {...props} d="M5 7h14M5 12h14M5 17h9" />
}

export function IconWidgetImage(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M4 5h16v14H4V5zM8.5 10.5a1.5 1.5 0 100-3 1.5 1.5 0 000 3zM4 17l5-5 4 4 3-3 4 4"
    />
  )
}

export function IconWidgetGeneric(props: IconProps) {
  return <Svg {...props} d="M5 5h14v14H5z" />
}

export function IconSearch(props: IconProps) {
  return (
    <svg className={props.className} width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="11" cy="11" r="6" stroke="currentColor" strokeWidth="1.5" />
      <path d="M20 20l-4.35-4.35" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}
