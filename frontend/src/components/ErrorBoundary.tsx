import { Component, ErrorInfo, ReactNode } from 'react';

interface Props {
  children: ReactNode;
  /** changing this value (e.g. route path) resets the boundary after a crash */
  resetKey?: string;
}
interface State {
  error: Error | null;
}

/** Catches render-time exceptions in the page subtree so one broken screen
 * shows a recoverable fallback instead of unmounting the whole app to a blank
 * white page. Resets automatically when resetKey changes (route navigation). */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidUpdate(prev: Props) {
    if (prev.resetKey !== this.props.resetKey && this.state.error) {
      this.setState({ error: null });
    }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Page render error:', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="stack" style={{ maxWidth: 560, margin: '0 auto', paddingTop: 'var(--s7)' }}>
          <div className="card stack center" style={{ textAlign: 'center' }}>
            <div style={{ fontSize: '2.4rem', color: 'var(--terra)' }}>!</div>
            <h3>Đã xảy ra lỗi khi hiển thị trang</h3>
            <p className="muted">
              Trang này gặp sự cố không mong muốn. Bạn có thể thử lại hoặc chuyển sang mục khác — phần còn lại của ứng dụng vẫn hoạt động.
            </p>
            <div className="row" style={{ justifyContent: 'center' }}>
              <button className="btn btn-primary" onClick={() => this.setState({ error: null })}>
                Thử lại
              </button>
            </div>
            <details style={{ marginTop: 'var(--s3)', textAlign: 'left' }}>
              <summary className="faint mono" style={{ fontSize: '0.74rem', cursor: 'pointer' }}>
                Chi tiết kỹ thuật
              </summary>
              <pre className="mono" style={{ fontSize: '0.72rem', color: 'var(--ink-faint)', whiteSpace: 'pre-wrap', marginTop: 8 }}>
                {this.state.error.message}
              </pre>
            </details>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
